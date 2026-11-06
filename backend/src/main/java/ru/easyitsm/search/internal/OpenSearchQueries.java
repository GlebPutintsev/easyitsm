package ru.easyitsm.search.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.easyitsm.knowledge.api.ArticleReadScope;
import ru.easyitsm.tickets.api.TicketReadScope;
import ru.easyitsm.tickets.api.TicketType;

/**
 * Запросы к индексу OpenSearch. Фильтры прав повторяют {@link TicketReadScope#canRead} и
 * {@link ArticleReadScope#canRead}: это проверяет тест на настоящем OpenSearch, а выдачу всё равно
 * перепроверяют модули-владельцы.
 */
final class OpenSearchQueries {

    static final List<String> CLOSED = List.of("resolved", "closed", "cancelled");
    private static final List<String> SOURCE = List.of("entity_id", "ref", "title", "ticket_type", "status",
            "status_category", "priority", "updated_at");

    private OpenSearchQueries() {
    }

    static Map<String, Object> ticketAccess(TicketReadScope s) {
        List<Object> should = new ArrayList<>();
        List<String> groups = s.groupIds().stream().map(UUID::toString).sorted().toList();
        if (s.userId() != null) {
            should.add(terms("participants", List.of(s.userId().toString())));
        }
        if (!groups.isEmpty()) {
            should.add(Map.of("bool", Map.of("filter", List.of(term("confidential", true),
                    terms("security_group_id", groups)))));
        }
        List<Object> open = new ArrayList<>();
        if (s.allNonConfidential()) {
            open.add(Map.of("match_all", Map.of()));
        }
        if (s.groupScoped() && !groups.isEmpty()) {
            open.add(terms("group_id", groups));
        }
        if (s.unrouted()) {
            open.add(Map.of("bool", Map.of("must_not", List.of(Map.of("exists", Map.of("field", "group_id"))))));
        }
        if (s.organizationId() != null) {
            open.add(term("organization_id", s.organizationId().toString()));
        }
        if (!open.isEmpty()) {
            should.add(Map.of("bool", Map.of("filter", List.of(term("confidential", false)),
                    "should", open, "minimum_should_match", 1)));
        }
        return anyOf(should);
    }

    static Map<String, Object> articleAccess(ArticleReadScope s) {
        if (s.everything()) {
            return Map.of("match_all", Map.of());
        }
        List<Object> should = new ArrayList<>();
        should.add(term("audience", "all"));
        if (s.employee()) {
            should.add(term("audience", "employees"));
        }
        if (s.staff()) {
            should.add(term("audience", "agents"));
        }
        if (!s.groupIds().isEmpty()) {
            should.add(Map.of("bool", Map.of("filter", List.of(term("audience", "group"),
                    terms("audience_group_id", s.groupIds().stream().map(UUID::toString).sorted().toList())))));
        }
        return anyOf(should);
    }

    /** Поиск заявок: текст, права, фильтры, подсветка. */
    static Map<String, Object> tickets(UUID tenantId, SearchModels.Query q, TicketReadScope scope) {
        List<Object> filter = new ArrayList<>(List.of(term("tenant_id", tenantId.toString()), term("kind", "ticket"),
                ticketAccess(scope)));
        if (!q.ticketTypes().isEmpty()) {
            filter.add(terms("ticket_type", q.ticketTypes().stream().map(TicketType::code).sorted().toList()));
        }
        List<Object> mustNot = new ArrayList<>();
        if ("active".equals(q.state())) {
            mustNot.add(terms("status_category", CLOSED));
        } else if ("closed".equals(q.state())) {
            filter.add(terms("status_category", CLOSED));
        }
        if (q.updatedFrom() != null) {
            filter.add(Map.of("range", Map.of("updated_at", Map.of("gte", q.updatedFrom().toString()))));
        }
        List<String> fields = new ArrayList<>(List.of("title^3", "body", "public_text"));
        if (scope.seesInternal()) {
            fields.add("internal_text");
        }
        return body(q, filter, mustNot, fields);
    }

    static Map<String, Object> articles(UUID tenantId, SearchModels.Query q, ArticleReadScope scope) {
        List<Object> filter = List.of(term("tenant_id", tenantId.toString()), term("kind", "article"),
                articleAccess(scope));
        return body(q, filter, List.of(), List.of("title^3", "body"));
    }

    private static Map<String, Object> body(SearchModels.Query q, List<Object> filter, List<Object> mustNot,
                                            List<String> fields) {
        String text = q.text().strip();
        Map<String, Object> words = new LinkedHashMap<>();
        words.put("query", text);
        words.put("type", "bool_prefix");
        words.put("fields", fields);
        words.put("operator", "and");
        words.put("fuzziness", "AUTO");
        words.put("prefix_length", 2);
        Map<String, Object> textMatch = Map.of("bool", Map.of("should", List.of(
                Map.of("multi_match", words),
                Map.of("term", Map.of("ref", Map.of("value", text, "boost", 20)))), "minimum_should_match", 1));
        Map<String, Object> phrase = Map.of("multi_match", Map.of("query", text, "type", "phrase", "slop", 2,
                "fields", fields.stream().map(f -> f.contains("^") ? f : f + "^2").toList()));

        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("filter", filter);
        bool.put("must", List.of(textMatch));
        bool.put("should", List.of(phrase));
        if (!mustNot.isEmpty()) {
            bool.put("must_not", mustNot);
        }
        Map<String, Object> hlFields = new LinkedHashMap<>();
        hlFields.put("title", Map.of("number_of_fragments", 0));
        for (String f : fields) {
            String name = f.replaceAll("\\^.*$", "");
            if (!name.equals("title")) {
                hlFields.put(name, Map.of("fragment_size", 180, "number_of_fragments", 1, "no_match_size", 0));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", q.limit());
        body.put("track_total_hits", 1000);
        body.put("_source", SOURCE);
        body.put("query", Map.of("bool", bool));
        body.put("highlight", Map.of(
                "pre_tags", List.of(String.valueOf(SearchModels.MARK_START)),
                "post_tags", List.of(String.valueOf(SearchModels.MARK_END)),
                "encoder", "default",
                "fields", hlFields));
        return body;
    }

    private static Map<String, Object> anyOf(List<Object> should) {
        if (should.isEmpty()) {
            return Map.of("match_none", Map.of());
        }
        return Map.of("bool", Map.of("should", should, "minimum_should_match", 1));
    }

    private static Map<String, Object> term(String field, Object value) {
        return Map.of("term", Map.of(field, value));
    }

    private static Map<String, Object> terms(String field, List<String> values) {
        return Map.of("terms", Map.of(field, values));
    }
}
