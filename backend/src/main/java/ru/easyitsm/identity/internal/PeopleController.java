package ru.easyitsm.identity.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.web.ApiException;

/**
 * Имена людей для любого вошедшего: заявителя на портале, агента в очереди. В отличие от {@code /users}
 * (управление, только сотрудники поддержки) отдаёт лишь то, что нужно показать рядом с записью: имя и почту.
 * Роли, группы и прочие атрибуты здесь не раскрываются.
 * <ul>
 *   <li>{@code ?ids=a,b,c} — до 100 id; заблокированные тоже, с {@code active=false}, чтобы у старых записей
 *       оставалось имя автора; неизвестные id в ответ не попадают;</li>
 *   <li>{@code ?q=ив&limit=10} — поиск по имени, логину и почте, только активные, для выбора коллеги.</li>
 * </ul>
 */
@RestController
class PeopleController {

    static final int MAX_IDS = 100;
    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 50;

    record Person(UUID id, String displayName, String email, boolean active) {
    }

    private final JdbcClient jdbc;

    PeopleController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/people")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    List<Person> people(@RequestParam(required = false) String ids,
                        @RequestParam(required = false) String q,
                        @RequestParam(required = false) Integer limit) {
        if (ids != null) {
            return byIds(parseIds(ids));
        }
        if (q == null || q.isBlank()) {
            throw ApiException.badRequest("people_query_required", "Укажите ids или q");
        }
        int l = limit == null ? DEFAULT_LIMIT : limit;
        if (l < 1 || l > MAX_LIMIT) {
            throw ApiException.badRequest("invalid_limit", "limit должен быть от 1 до " + MAX_LIMIT);
        }
        return search(q.trim(), l);
    }

    private List<Person> byIds(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                select id, display_name, email, state from user_account
                 where id = any(:ids)
                 order by lower(display_name), id""")
                .param("ids", ids.toArray(UUID[]::new))
                .query((rs, n) -> person(rs))
                .list();
    }

    private List<Person> search(String q, int limit) {
        String like = "%" + q.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                + "%";
        return jdbc.sql("""
                select id, display_name, email, state from user_account
                 where state = 'active'
                   and (lower(display_name) like :q or lower(login) like :q or lower(coalesce(email, '')) like :q)
                 order by lower(display_name), id
                 limit :limit""")
                .param("q", like).param("limit", limit)
                .query((rs, n) -> person(rs))
                .list();
    }

    private static Person person(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Person(rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("email"),
                "active".equals(rs.getString("state")));
    }

    static Set<UUID> parseIds(String ids) {
        Set<UUID> out = new LinkedHashSet<>();
        List<String> parts = new ArrayList<>();
        for (String part : ids.split(",")) {
            if (!part.isBlank()) {
                parts.add(part.trim());
            }
        }
        if (parts.size() > MAX_IDS) {
            throw ApiException.badRequest("too_many_ids", "Не больше " + MAX_IDS + " id за запрос");
        }
        for (String part : parts) {
            try {
                out.add(UUID.fromString(part));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("invalid_id", "Неверный id: " + part);
            }
        }
        return out;
    }
}
