package ru.easyitsm.tickets.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.tickets.api.Priority;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketReadScope;
import ru.easyitsm.tickets.api.TicketSearchDocument;
import ru.easyitsm.tickets.api.TicketSearchHit;
import ru.easyitsm.tickets.api.TicketSearchIndex;
import ru.easyitsm.tickets.api.TicketTextQuery;
import ru.easyitsm.tickets.api.TicketType;

/**
 * Заявки для модуля поиска: документы индекса, область чтения и резервный поиск по {@code search_vector}.
 * Права — те же {@link TicketAccess#filter} и {@link TicketAccess#scope}, что у REST заявок.
 */
@Component
class TicketIndexing implements TicketSearchIndex {

    /** Сколько текста ленты брать в индекс: длинные переписки обрезаются, тема и описание — целиком. */
    static final int FEED_TEXT_LIMIT = 100_000;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final int MAX_TERMS = 16;
    private static final String HEADLINE = "MaxFragments=1, MaxWords=35, MinWords=12, StartSel=" + MARK_START
            + ", StopSel=" + MARK_END + ", HighlightAll=false";

    private final TicketStore store;
    private final TicketAccess access;

    TicketIndexing(TicketStore store, TicketAccess access) {
        this.store = store;
        this.access = access;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TicketSearchDocument> document(UUID ticketId) {
        return store.find(ticketId).map(t -> {
            Set<UUID> participants = new HashSet<>(store.jdbc()
                    .sql("select user_id from ticket_watcher where ticket_id = :id")
                    .param("id", t.id).query(UUID.class).list());
            for (UUID u : new UUID[] {t.requesterId, t.createdForId, t.assigneeId, t.createdBy}) {
                if (u != null) {
                    participants.add(u);
                }
            }
            Map<String, String> feed = new LinkedHashMap<>();
            store.jdbc().sql("""
                            select visibility, left(string_agg(body, E'\\n' order by id), :limit) as text
                              from ticket_activity
                             where ticket_id = :id and kind in ('reply', 'note') and body <> ''
                             group by visibility""")
                    .param("id", t.id).param("limit", FEED_TEXT_LIMIT)
                    .query((rs, n) -> feed.put(rs.getString("visibility"), rs.getString("text"))).list();
            return new TicketSearchDocument(t.id, t.number, t.type, t.title, t.description,
                    feed.getOrDefault("public", ""), feed.getOrDefault("internal", ""), t.status, t.statusCategory,
                    t.priority, t.createdAt, t.updatedAt, participants, t.groupId, t.securityGroupId, t.confidential,
                    t.organizationId);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> idsAfter(UUID afterId, int limit) {
        return store.jdbc().sql("select id from ticket where (cast(:after as uuid) is null or id > cast(:after as uuid))"
                        + " order by id limit :limit")
                .param("after", afterId).param("limit", Math.max(1, Math.min(limit, 5000)))
                .query(UUID.class).list();
    }

    @Override
    public TicketReadScope readScope() {
        Actor actor = Actor.current();
        AccessScopes.Scope s = access.scope(actor);
        return new TicketReadScope(actor.userId(), s.groupIds(), s.allNonConfidential(), s.groupScoped(),
                s.unrouted(), s.organizationId(), actor.canSeeInternal());
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> visibleAmong(Collection<UUID> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            return Set.of();
        }
        Map<String, Object> p = new LinkedHashMap<>();
        String filter = access.filter(Actor.current(), p);
        p.put("ids", Sql.array(new LinkedHashSet<>(ticketIds)));
        return new HashSet<>(store.jdbc()
                .sql("select t.id from ticket t where t.id = any(cast(:ids as uuid[])) and " + filter)
                .params(p).query(UUID.class).list());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TicketSearchHit> search(TicketTextQuery q) {
        String number = q.text() == null ? "" : q.text().strip().toUpperCase(Locale.ROOT);
        String tsq = tsQuery(q.text());
        if (tsq == null && number.isEmpty()) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder(access.filter(Actor.current(), p));
        p.put("number", number);
        p.put("tsq", tsq == null ? "" : tsq);
        where.append(tsq == null
                ? " and t.number = :number"
                : " and (t.search_vector @@ to_tsquery('russian', :tsq) or t.number = :number)");
        if (!q.types().isEmpty()) {
            p.put("types", Sql.array(q.types().stream().map(TicketType::code).toList()));
            where.append(" and t.type = any(cast(:types as text[]))");
        }
        if ("active".equals(q.state())) {
            where.append(" and t.status_category not in ('resolved', 'closed', 'cancelled')");
        } else if ("closed".equals(q.state())) {
            where.append(" and t.status_category in ('resolved', 'closed', 'cancelled')");
        }
        if (q.updatedFrom() != null) {
            p.put("updatedFrom", Sql.ts(q.updatedFrom()));
            where.append(" and t.updated_at >= :updatedFrom");
        }
        p.put("headline", HEADLINE);
        p.put("limit", Math.max(1, Math.min(q.limit(), 100)));
        String sql = """
                select t.id, t.number, t.type, t.title, t.status, t.status_category, t.priority, t.updated_at,
                       case when :tsq = '' then left(t.description, 240)
                            else ts_headline('russian', t.description, to_tsquery('russian', :tsq), :headline) end
                           as snippet,
                       case when t.number = :number then 10.0
                            when :tsq = '' then 0
                            else ts_rank(t.search_vector, to_tsquery('russian', :tsq)) end as rank
                  from ticket t
                 where %s
                 order by rank desc, t.updated_at desc, t.id desc
                 limit :limit""".formatted(where);
        return store.jdbc().sql(sql).params(p).query(TicketIndexing::hit).list();
    }

    private static TicketSearchHit hit(ResultSet rs, int n) throws SQLException {
        return new TicketSearchHit(Sql.uuid(rs, "id"), rs.getString("number"),
                TicketType.fromCode(rs.getString("type")), rs.getString("title"), rs.getString("status"),
                StatusCategory.fromCode(rs.getString("status_category")), Priority.fromCode(rs.getString("priority")),
                Sql.instant(rs, "updated_at"), rs.getString("snippet"), rs.getDouble("rank"));
    }

    /**
     * Безопасный tsquery из пользовательского текста: только буквы и цифры, все слова обязательны, поиск по
     * началу слова, чтобы находилось при наборе.
     */
    static String tsQuery(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Set<String> terms = new LinkedHashSet<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find() && terms.size() < MAX_TERMS) {
            terms.add(m.group());
        }
        if (terms.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>(terms.size());
        for (String t : terms) {
            parts.add(t + ":*");
        }
        return String.join(" & ", parts);
    }
}
