package ru.easyitsm.knowledge.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Списки и поиск статей на PostgreSQL: полнотекстовый поиск с русской морфологией по опубликованной версии
 * и поиск по заголовку рабочей копии. Права применяются в самом запросе (ACC-02), поэтому пагинация честная.
 * TODO(поиск): перейти на OpenSearch вместе с глобальным поиском, оставив этот путь резервным (NFR-04).
 */
@Component
class ArticleSearch {

    /** Сколько символов текста брать для фрагмента: целиком длинные статьи в список не тянем. */
    private static final int SNIPPET_SOURCE = 2000;
    static final int SNIPPET_LENGTH = 220;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final int MAX_TERMS = 24;

    /** Видимость опубликованной версии по аудитории — то же, что {@link ArticleAccess#canReadPublished}. */
    private static final String READABLE = """
            (a.published_version_no is not null and a.status <> 'archived' and (
                :everything
                or a.audience = 'all'
                or (a.audience = 'employees' and :employee)
                or (a.audience = 'agents' and :staff)
                or (a.audience = 'group' and a.audience_group_id::text = any(string_to_array(:groups, ',')))))""";

    /** Доступ к рабочей копии — то же, что {@link ArticleAccess#canReadWorkspace}. */
    private static final String WORKSPACE = """
            (:everything or a.owner_id = :me or a.created_by = :me or (:editor and %s))""".formatted(READABLE);

    enum View { PORTAL, WORKSPACE }

    record Filter(View view, String q, String status, UUID categoryId, boolean mine, boolean awaitingMyReview) {
    }

    /** Строка выборки и значение ключа сортировки для курсора. */
    record Row(KnowledgeDtos.ArticleListItem item, Instant sortAt) {
    }

    private final JdbcClient jdbc;

    ArticleSearch(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Страница списка. С поисковой строкой — лучшие {@code limit} совпадений по релевантности без курсора,
     * без неё — keyset по дате публикации (портал) или изменения (рабочее место).
     */
    List<Row> page(Reader reader, Filter f, Instant afterSortAt, UUID afterId, int limit) {
        boolean portal = f.view() == View.PORTAL;
        String allTerms = tsQuery(f.q(), " & ", true);
        boolean searching = allTerms != null;
        String sortColumn = portal ? "a.published_at" : "a.updated_at";

        StringBuilder sql = new StringBuilder("select a.id, a.number, a.status, a.audience, a.category_id, a.owner_id, ")
                .append("a.current_version_no, a.published_version_no, a.published_at, a.review_at, a.updated_at, ")
                .append(portal ? "a.published_title" : "a.title").append(" as title, ")
                .append(sortColumn).append(" as sort_at, ");
        if (searching && portal) {
            sql.append("ts_headline('russian', v.body, to_tsquery('russian', :tsq), ")
                    .append("'MaxFragments=1, MaxWords=40, MinWords=15, StartSel=\"\", StopSel=\"\"') as excerpt, ");
        } else {
            sql.append("left(v.body, ").append(SNIPPET_SOURCE).append(") as excerpt, ");
        }
        sql.append(searching ? "ts_rank(a.search_vector, to_tsquery('russian', :tsq))" : "0").append(" as rank ")
                .append("from article a join article_version v on v.article_id = a.id and v.version_no = ")
                .append(portal ? "a.published_version_no" : "a.current_version_no")
                .append(" where ").append(portal ? READABLE : WORKSPACE);

        if (f.categoryId() != null) {
            sql.append(" and a.category_id = :category");
        }
        if (!portal && f.status() != null) {
            sql.append(" and a.status = :status");
        }
        if (!portal && f.mine()) {
            sql.append(" and (a.owner_id = :me or a.created_by = :me)");
        }
        if (!portal && f.awaitingMyReview()) {
            sql.append(" and a.status = 'review' and (a.reviewer_id = :me or a.reviewer_id is null)")
                    .append(" and a.created_by <> :me");
        }
        if (searching) {
            sql.append(portal
                    ? " and a.search_vector @@ to_tsquery('russian', :tsq)"
                    : " and (a.search_vector @@ to_tsquery('russian', :tsq) or a.title ilike :like escape '\\')");
            sql.append(" order by rank desc, ").append(sortColumn).append(" desc, a.id desc");
        } else {
            if (afterSortAt != null) {
                sql.append(" and (").append(sortColumn).append(", a.id) < (:afterSortAt, :afterId)");
            }
            sql.append(" order by ").append(sortColumn).append(" desc, a.id desc");
        }
        sql.append(" limit :limit");

        var spec = bindReader(jdbc.sql(sql.toString()), reader).param("limit", limit);
        if (f.categoryId() != null) {
            spec = spec.param("category", f.categoryId());
        }
        if (!portal && f.status() != null) {
            spec = spec.param("status", f.status());
        }
        if (searching) {
            spec = spec.param("tsq", allTerms);
            if (!portal) {
                spec = spec.param("like", "%" + escapeLike(f.q().strip()) + "%");
            }
        } else if (afterSortAt != null) {
            spec = spec.param("afterSortAt", Timestamp.from(afterSortAt)).param("afterId", afterId);
        }
        return spec.query(this::mapRow).list();
    }

    /** Подсказки по тексту обращения: любое из слов, только опубликованные и видимые читателю. */
    List<KnowledgeDtos.ArticleListItem> suggest(Reader reader, String text, int limit) {
        String anyTerms = tsQuery(text, " | ", false);
        if (anyTerms == null) {
            return List.of();
        }
        String sql = """
                select a.id, a.number, a.status, a.audience, a.category_id, a.owner_id, a.current_version_no,
                       a.published_version_no, a.published_at, a.review_at, a.updated_at, a.published_title as title,
                       a.published_at as sort_at,
                       ts_headline('russian', v.body, to_tsquery('russian', :tsq),
                                   'MaxFragments=1, MaxWords=30, MinWords=10, StartSel="", StopSel=""') as excerpt,
                       ts_rank(a.search_vector, to_tsquery('russian', :tsq)) as rank
                  from article a
                  join article_version v on v.article_id = a.id and v.version_no = a.published_version_no
                 where %s and a.search_vector @@ to_tsquery('russian', :tsq)
                 order by rank desc, a.published_at desc
                 limit :limit""".formatted(READABLE);
        return bindReader(jdbc.sql(sql), reader).param("tsq", anyTerms).param("limit", limit)
                .query(this::mapRow).list().stream().map(Row::item).toList();
    }

    private static JdbcClient.StatementSpec bindReader(JdbcClient.StatementSpec spec, Reader r) {
        return spec.param("everything", r.seesEverything())
                .param("employee", r.employee())
                .param("staff", r.staff())
                .param("editor", r.editor())
                .param("groups", String.join(",", r.groupArray()))
                .param("me", r.userId());
    }

    private Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        Integer published = (Integer) rs.getObject("published_version_no");
        var item = new KnowledgeDtos.ArticleListItem(
                rs.getObject("id", UUID.class),
                "KB-" + rs.getLong("number"),
                rs.getString("title"),
                Snippets.plain(rs.getString("excerpt"), SNIPPET_LENGTH),
                rs.getString("status"),
                rs.getString("audience"),
                rs.getObject("category_id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getInt("current_version_no"),
                published,
                instant(rs, "published_at"),
                instant(rs, "review_at"),
                instant(rs, "updated_at"));
        return new Row(item, instant(rs, "sort_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    /**
     * Безопасный tsquery из пользовательского текста: только буквы и цифры, операторы ставим сами.
     * {@code prefix} — искать по началу слова, чтобы поиск работал при наборе.
     *
     * @return null, если в тексте нет слов
     */
    static String tsQuery(String text, String operator, boolean prefix) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Set<String> terms = new LinkedHashSet<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find() && terms.size() < MAX_TERMS) {
            String w = m.group();
            if (w.length() >= 2 || Character.isDigit(w.charAt(0))) {
                terms.add(w);
            }
        }
        if (terms.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>(terms.size());
        for (String t : terms) {
            parts.add(prefix ? t + ":*" : t);
        }
        return String.join(operator, parts);
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
