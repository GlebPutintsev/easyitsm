package ru.easyitsm.search.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.easyitsm.knowledge.api.ArticleSearchDocument;
import ru.easyitsm.tickets.api.TicketSearchDocument;

/** Документы индекса OpenSearch из документов модулей. Схема — {@code search/index-v1.json}. */
final class SearchDocuments {

    private SearchDocuments() {
    }

    static String id(UUID tenantId, IndexQueue.Kind kind, UUID entityId) {
        return tenantId + ":" + kind.code() + ":" + entityId;
    }

    static Map<String, Object> ticket(UUID tenantId, TicketSearchDocument t, Instant now) {
        Map<String, Object> d = base(tenantId, IndexQueue.Kind.TICKET, t.id(), t.number(), t.title(), now);
        d.put("body", t.description());
        d.put("public_text", t.publicText());
        d.put("internal_text", t.internalText());
        d.put("ticket_type", t.type().code());
        d.put("status", t.statusCode());
        d.put("status_category", t.statusCategory().code());
        d.put("priority", t.priority().code());
        d.put("created_at", t.createdAt() == null ? null : t.createdAt().toString());
        d.put("updated_at", t.updatedAt() == null ? null : t.updatedAt().toString());
        d.put("participants", t.participantIds().stream().map(UUID::toString).sorted().toList());
        d.put("group_id", str(t.groupId()));
        d.put("security_group_id", str(t.securityGroupId()));
        d.put("confidential", t.confidential());
        d.put("organization_id", str(t.organizationId()));
        return d;
    }

    static Map<String, Object> article(UUID tenantId, ArticleSearchDocument a, Instant now) {
        Map<String, Object> d = base(tenantId, IndexQueue.Kind.ARTICLE, a.id(), a.key(), a.title(), now);
        d.put("body", a.body());
        d.put("status", a.status());
        d.put("audience", a.audience().code());
        d.put("audience_group_id", str(a.audienceGroupId()));
        d.put("category_id", str(a.categoryId()));
        d.put("updated_at", a.publishedAt() == null ? null : a.publishedAt().toString());
        d.put("participants", List.of());
        return d;
    }

    private static Map<String, Object> base(UUID tenantId, IndexQueue.Kind kind, UUID id, String ref, String title,
                                            Instant now) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("tenant_id", tenantId.toString());
        d.put("kind", kind.code());
        d.put("entity_id", id.toString());
        d.put("ref", ref);
        d.put("title", title);
        d.put("indexed_at", now.toString());
        return d;
    }

    private static String str(UUID id) {
        return id == null ? null : id.toString();
    }
}
