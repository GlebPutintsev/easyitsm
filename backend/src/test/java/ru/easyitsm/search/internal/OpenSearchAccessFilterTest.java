package ru.easyitsm.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.easyitsm.knowledge.api.ArticleAudience;
import ru.easyitsm.knowledge.api.ArticleReadScope;
import ru.easyitsm.knowledge.api.ArticleSearchDocument;
import ru.easyitsm.tickets.api.Priority;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketReadScope;
import ru.easyitsm.tickets.api.TicketSearchDocument;
import ru.easyitsm.tickets.api.TicketType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Фильтр прав в запросе к OpenSearch эквивалентен правилу чтения модулей ({@link TicketReadScope#canRead},
 * {@link ArticleReadScope#canRead}): все сочетания атрибутов документа против всех сочетаний области.
 */
@EnabledIfEnvironmentVariable(named = "EASYITSM_TEST_OPENSEARCH_URL", matches = ".+")
class OpenSearchAccessFilterTest {

    static final UUID TENANT = UUID.randomUUID();
    static final UUID ME = UUID.randomUUID();
    static final UUID G1 = UUID.randomUUID();
    static final UUID G2 = UUID.randomUUID();
    static final UUID ORG = UUID.randomUUID();
    static final String PREFIX = "easyitsm-acl-" + UUID.randomUUID().toString().substring(0, 8);

    static OpenSearchClient client;
    static SearchIndex index;
    static final Map<UUID, TicketSearchDocument> tickets = new HashMap<>();
    static final Map<UUID, ArticleSearchDocument> articles = new HashMap<>();

    @BeforeAll
    static void indexDocuments() {
        SearchProperties props = new SearchProperties(new SearchProperties.OpenSearch(true,
                List.of(System.getenv("EASYITSM_TEST_OPENSEARCH_URL")), null, null, PREFIX, 1, 0,
                Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30)),
                new SearchProperties.Indexing(Duration.ofSeconds(1), 200, Duration.ofMinutes(2), "-"), 20);
        JsonMapper json = JsonMapper.builder().build();
        client = new OpenSearchClient(props, json);
        index = new SearchIndex(client, props, json);
        index.ensure();

        List<SearchIndex.Op> ops = new ArrayList<>();
        Instant now = Instant.now();
        UUID[] groups = {null, G1, G2};
        for (boolean participant : new boolean[] {false, true}) {
            for (boolean confidential : new boolean[] {false, true}) {
                for (UUID group : groups) {
                    for (UUID security : groups) {
                        for (UUID org : new UUID[] {null, ORG}) {
                            UUID id = UUID.randomUUID();
                            var d = new TicketSearchDocument(id, "INC-1", TicketType.INCIDENT, "Доступ", "", "", "",
                                    "new", StatusCategory.NEW, Priority.P3, now, now,
                                    participant ? Set.of(ME, UUID.randomUUID()) : Set.of(UUID.randomUUID()), group,
                                    security, confidential, org);
                            tickets.put(id, d);
                            ops.add(new SearchIndex.Op(SearchDocuments.id(TENANT, IndexQueue.Kind.TICKET, id),
                                    SearchDocuments.ticket(TENANT, d, now)));
                        }
                    }
                }
            }
        }
        for (ArticleAudience audience : ArticleAudience.values()) {
            for (UUID group : audience == ArticleAudience.GROUP ? new UUID[] {G1, G2} : new UUID[] {null}) {
                UUID id = UUID.randomUUID();
                var a = new ArticleSearchDocument(id, "KB-1", "Доступ", "", "published", audience, group, null, now);
                articles.put(id, a);
                ops.add(new SearchIndex.Op(SearchDocuments.id(TENANT, IndexQueue.Kind.ARTICLE, id),
                        SearchDocuments.article(TENANT, a, now)));
            }
        }
        assertThat(index.bulk(ops)).allMatch(SearchIndex.OpResult::ok);
        client.post("/" + index.alias() + "/_refresh", Map.of());
    }

    @AfterAll
    static void dropIndex() {
        if (client != null) {
            client.delete("/" + PREFIX + "-v1-000001");
        }
    }

    @Test
    void ticketFilterMatchesReadRule() {
        int checked = 0;
        for (UUID user : new UUID[] {ME, UUID.randomUUID()}) {
            for (Set<UUID> groups : List.of(Set.<UUID>of(), Set.of(G1), Set.of(G1, G2))) {
                for (boolean all : new boolean[] {false, true}) {
                    for (boolean scoped : new boolean[] {false, true}) {
                        for (boolean unrouted : new boolean[] {false, true}) {
                            for (UUID org : new UUID[] {null, ORG}) {
                                var scope = new TicketReadScope(user, groups, all, scoped, unrouted, org, false);
                                Set<UUID> expected = new HashSet<>();
                                tickets.forEach((id, d) -> {
                                    if (scope.canRead(d)) {
                                        expected.add(id);
                                    }
                                });
                                assertThat(found("ticket", OpenSearchQueries.ticketAccess(scope)))
                                        .as("область %s", scope).isEqualTo(expected);
                                checked++;
                            }
                        }
                    }
                }
            }
        }
        assertThat(checked).isEqualTo(96);
    }

    @Test
    void articleFilterMatchesAudienceRule() {
        for (boolean everything : new boolean[] {false, true}) {
            for (boolean employee : new boolean[] {false, true}) {
                for (boolean staff : new boolean[] {false, true}) {
                    for (Set<UUID> groups : List.of(Set.<UUID>of(), Set.of(G1), Set.of(G1, G2))) {
                        var scope = new ArticleReadScope(everything, employee, staff, groups);
                        Set<UUID> expected = new HashSet<>();
                        articles.forEach((id, a) -> {
                            if (scope.canRead(a)) {
                                expected.add(id);
                            }
                        });
                        assertThat(found("article", OpenSearchQueries.articleAccess(scope)))
                                .as("область %s", scope).isEqualTo(expected);
                    }
                }
            }
        }
    }

    private Set<UUID> found(String kind, Map<String, Object> access) {
        var body = Map.of("size", 500, "_source", List.of("entity_id"), "query", Map.of("bool", Map.of("filter",
                List.of(Map.of("term", Map.of("tenant_id", TENANT.toString())), Map.of("term", Map.of("kind", kind)),
                        access))));
        OpenSearchClient.Response r = client.post("/" + index.alias() + "/_search", body);
        assertThat(r.ok()).as(r.body().toString()).isTrue();
        Set<UUID> ids = new HashSet<>();
        for (JsonNode h : r.body().path("hits").path("hits")) {
            ids.add(UUID.fromString(h.path("_source").path("entity_id").asString()));
        }
        return ids;
    }
}
