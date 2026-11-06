package ru.easyitsm.knowledge.internal;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.knowledge.api.ArticleAudience;
import ru.easyitsm.knowledge.api.ArticleReadScope;
import ru.easyitsm.knowledge.api.ArticleSearchDocument;
import ru.easyitsm.knowledge.api.ArticleSearchHit;
import ru.easyitsm.knowledge.api.ArticleSearchIndex;

/**
 * Опубликованные статьи для модуля поиска. Видимость — та же, что {@link ArticleAccess#canReadPublished}
 * и выражение READABLE в {@link ArticleSearch}.
 */
@Component
class ArticleIndexing implements ArticleSearchIndex {

    private static final String HEADLINE = "MaxFragments=1, MaxWords=35, MinWords=12, StartSel=" + MARK_START
            + ", StopSel=" + MARK_END;

    private static final String READABLE = """
            (a.published_version_no is not null and a.status <> 'archived' and (
                :everything
                or a.audience = 'all'
                or (a.audience = 'employees' and :employee)
                or (a.audience = 'agents' and :staff)
                or (a.audience = 'group' and a.audience_group_id = any(cast(:groups as uuid[])))))""";

    private final JdbcClient jdbc;

    ArticleIndexing(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ArticleSearchDocument> document(UUID articleId) {
        return jdbc.sql("""
                        select a.id, a.number, a.published_title, v.body, a.status, a.audience, a.audience_group_id,
                               a.category_id, a.published_at
                          from article a
                          join article_version v on v.article_id = a.id and v.version_no = a.published_version_no
                         where a.id = :id and a.status <> 'archived'""")
                .param("id", articleId)
                .query((rs, n) -> {
                    Timestamp published = rs.getTimestamp("published_at");
                    return new ArticleSearchDocument(rs.getObject("id", UUID.class), "KB-" + rs.getLong("number"),
                            rs.getString("published_title"), Snippets.searchable(rs.getString("body")),
                            rs.getString("status"), ArticleAudience.fromCode(rs.getString("audience")),
                            rs.getObject("audience_group_id", UUID.class), rs.getObject("category_id", UUID.class),
                            published == null ? null : published.toInstant());
                }).optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> idsAfter(UUID afterId, int limit) {
        return jdbc.sql("select id from article where (cast(:after as uuid) is null or id > cast(:after as uuid))"
                        + " order by id limit :limit")
                .param("after", afterId).param("limit", Math.max(1, Math.min(limit, 5000)))
                .query(UUID.class).list();
    }

    @Override
    public ArticleReadScope readScope() {
        Reader r = Reader.current();
        return new ArticleReadScope(r.seesEverything(), r.employee(), r.staff(), r.groups());
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> readableAmong(Collection<UUID> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(bind(jdbc.sql("select a.id from article a where a.id = any(cast(:ids as uuid[])) and "
                        + READABLE), Reader.current())
                .param("ids", array(new LinkedHashSet<>(articleIds)))
                .query(UUID.class).list());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArticleSearchHit> search(String text, int limit) {
        String tsq = ArticleSearch.tsQuery(text, " & ", true);
        if (tsq == null) {
            return List.of();
        }
        String sql = """
                select a.id, a.number, a.published_title, a.category_id, a.published_at,
                       ts_headline('russian', v.body, to_tsquery('russian', :tsq), :headline) as snippet,
                       ts_rank(a.search_vector, to_tsquery('russian', :tsq)) as rank
                  from article a
                  join article_version v on v.article_id = a.id and v.version_no = a.published_version_no
                 where %s and a.search_vector @@ to_tsquery('russian', :tsq)
                 order by rank desc, a.published_at desc, a.id desc
                 limit :limit""".formatted(READABLE);
        return bind(jdbc.sql(sql), Reader.current())
                .param("tsq", tsq).param("headline", HEADLINE).param("limit", Math.max(1, Math.min(limit, 100)))
                .query((rs, n) -> {
                    Timestamp published = rs.getTimestamp("published_at");
                    return new ArticleSearchHit(rs.getObject("id", UUID.class), "KB-" + rs.getLong("number"),
                            rs.getString("published_title"), Snippets.plain(rs.getString("snippet"), 400),
                            rs.getObject("category_id", UUID.class),
                            published == null ? null : published.toInstant(), rs.getDouble("rank"));
                }).list();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Reader r) {
        return spec.param("everything", r.seesEverything())
                .param("employee", r.employee())
                .param("staff", r.staff())
                .param("groups", array(r.groups()));
    }

    private static String array(Collection<UUID> ids) {
        return ids.isEmpty() ? "{}" : "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
    }
}
