package ru.easyitsm.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ru.easyitsm.knowledge.KnowledgeApiSupport;
import ru.easyitsm.knowledge.KnowledgeApiSupport.User;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Глобальный поиск через настоящий OpenSearch: индексация по событиям, морфология, права, внутренние заметки,
 * перепроверка устаревшего индекса. Запускается, если задан {@code EASYITSM_TEST_OPENSEARCH_URL}.
 */
// Свой контекст с особыми настройками: закрываем после класса, чтобы не держать соединения с БД (их 100 на всех).
@DirtiesContext
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "EASYITSM_TEST_OPENSEARCH_URL", matches = ".+")
class OpenSearchSearchTest {

    private static final String PREFIX = "easyitsm-test-" + UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void openSearch(DynamicPropertyRegistry r) {
        r.add("easyitsm.search.opensearch.enabled", () -> "true");
        r.add("easyitsm.search.opensearch.urls", () -> System.getenv("EASYITSM_TEST_OPENSEARCH_URL"));
        r.add("easyitsm.search.opensearch.index-prefix", () -> PREFIX);
        r.add("easyitsm.search.indexing.batch-size", () -> "1000");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    SearchApiSupport s;
    User requester;
    User stranger;
    User agent;
    User otherAgent;
    String group;
    String word;

    @BeforeEach
    void setUp() {
        s = new SearchApiSupport(mvc, jdbc);
        requester = s.user("REQ");
        stranger = s.user("REQ");
        agent = s.user("AGT");
        otherAgent = s.user("AGT");
        group = s.group(agent);
        s.group(otherAgent);
        word = SearchApiSupport.marker();
        // Первая загрузка индекса из БД: пока она идёт, поиск отвечает из PostgreSQL.
        await(Duration.ofMinutes(3), () -> ready());
    }

    @Test
    void indexedTicketFoundByWordFormsWithinSeconds() throws Exception {
        String id = s.ticket(requester, "Не печатает принтер " + word, "Принтер на третьем этаже", group);
        Instant created = Instant.now();
        await(Duration.ofSeconds(10), () -> found(requester, "принтеры " + word, id));
        assertThat(Duration.between(created, Instant.now())).isLessThan(Duration.ofSeconds(10)); // NFR-04

        String json = s.search(requester, "печатать принтеры " + word, "ticket");
        assertThat((String) JsonPath.read(json, "$.engine")).isEqualTo("opensearch");
        assertThat((Boolean) JsonPath.read(json, "$.degraded")).isFalse();
        List<Boolean> titleMarks = JsonPath.read(json, "$.groups[0].items[0].title[*].hl");
        assertThat(titleMarks).contains(true);

        assertThat(SearchApiSupport.ids(s.search(agent, "принтер " + word, "ticket"), "ticket")).containsExactly(id);
        assertThat(SearchApiSupport.ids(s.search(stranger, "принтер " + word, "ticket"), "ticket")).isEmpty();
        assertThat(SearchApiSupport.ids(s.search(otherAgent, "принтер " + word, "ticket"), "ticket")).isEmpty();
    }

    @Test
    void internalNotesOnlyForSupport() throws Exception {
        String id = s.ticket(requester, "Медленный интернет", "Страницы открываются долго", group);
        mvc.perform(agent.sign(post("/api/v1/tickets/" + id + "/activity")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Проверили коммутатор " + word + "\",\"visibility\":\"internal\"}"))
                .andExpect(status().isCreated());
        await(Duration.ofSeconds(10), () -> found(agent, "коммутаторы " + word, id));
        assertThat(SearchApiSupport.ids(s.search(requester, "коммутатор " + word, "ticket"), "ticket")).isEmpty();
    }

    @Test
    void staleIndexIsRecheckedByTickets() throws Exception {
        String id = s.ticket(requester, "Сломался сканер " + word, "Сканер не включается", group);
        await(Duration.ofSeconds(10), () -> found(agent, "сканер " + word, id));
        // Заявку перевели в чужую группу в обход событий: индекс устарел, но выдачу перепроверяет tickets.
        String other = s.group(otherAgent);
        TenantContext.runAs(UUID.fromString(SearchApiSupport.DEMO), () -> jdbc.sql(
                        "update ticket set group_id = cast(:g as uuid) where id = cast(:id as uuid)")
                .param("g", other).param("id", id).update());
        assertThat(SearchApiSupport.ids(s.search(agent, "сканер " + word, "ticket"), "ticket")).isEmpty();
    }

    @Test
    void articlesIndexedOnPublishAndRemovedOnArchive() throws Exception {
        User knw = s.user("KNW");
        User author = s.user("AGT");
        String id = s.kb.published(author, knw, "Настройка сетевых принтеров " + word, "Откройте панель " + word,
                "all");
        await(Duration.ofSeconds(10), () -> found(requester, "сетевой принтер " + word, id));
        s.kb.action(knw, id, "archive", "{}").andExpect(status().isOk());
        await(Duration.ofSeconds(10), () -> !found(requester, "сетевой принтер " + word, id));
    }

    @Test
    void statusShowsReadyIndex() throws Exception {
        User admin = s.user("ADM");
        mvc.perform(admin.sign(get("/api/v1/search/status"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.openSearchEnabled").value(true))
                .andExpect(jsonPath("$.openSearchAvailable").value(true))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.index").value(PREFIX + "-v1"));
        mvc.perform(admin.sign(post("/api/v1/search/reindex"))).andExpect(status().isAccepted());
    }

    private boolean ready() {
        boolean[] ok = {false};
        TenantContext.runAs(UUID.fromString(SearchApiSupport.DEMO), () -> ok[0] = jdbc
                .sql("select coalesce(bool_and(ready_at is not null and index_name = :i), false) from search_index_state")
                .param("i", PREFIX + "-v1").query(Boolean.class).single());
        return ok[0];
    }

    private boolean found(User u, String q, String id) {
        try {
            String json = s.search(u, q, null);
            return JsonPath.<String>read(json, "$.engine").equals("opensearch")
                    && (SearchApiSupport.ids(json, "ticket").contains(id) || SearchApiSupport.ids(json, "article")
                    .contains(id));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void await(Duration timeout, Supplier<Boolean> condition) {
        Instant end = Instant.now().plus(timeout);
        while (!condition.get()) {
            if (Instant.now().isAfter(end)) {
                throw new AssertionError("Не дождались условия за " + timeout);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    static String q(String s) {
        return KnowledgeApiSupport.q(s);
    }
}
