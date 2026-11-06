package ru.easyitsm.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.easyitsm.knowledge.KnowledgeApiSupport.User;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * AT-08: OpenSearch настроен, но недоступен. Заявки создаются, поиск отвечает из PostgreSQL и помечает
 * ответ {@code degraded}, чтобы интерфейс показал предупреждение.
 */
// Свой контекст с особыми настройками: закрываем после класса, чтобы не держать соединения с БД (их 100 на всех).
@DirtiesContext
@SpringBootTest(properties = {
        "easyitsm.search.opensearch.enabled=true",
        "easyitsm.search.opensearch.urls=http://127.0.0.1:1",
        "easyitsm.search.opensearch.connect-timeout=PT0.2S"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenSearchDownTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void ticketsAreCreatedAndSearchFallsBackToPostgres() throws Exception {
        SearchApiSupport s = new SearchApiSupport(mvc, jdbc);
        User requester = s.user("REQ");
        String word = SearchApiSupport.marker();
        String id = s.ticket(requester, "Не работает телефон " + word, "Трубка молчит", null);

        String json = s.search(requester, "телефоны " + word, "ticket");
        assertThat((String) JsonPath.read(json, "$.engine")).isEqualTo("postgres");
        assertThat((Boolean) JsonPath.read(json, "$.degraded")).isTrue();
        assertThat(SearchApiSupport.ids(json, "ticket")).containsExactly(id);

        // Изменение не потерялось: ждёт в очереди индексации до восстановления OpenSearch.
        long[] queued = {0};
        TenantContext.runAs(UUID.fromString(SearchApiSupport.DEMO), () -> queued[0] = jdbc
                .sql("select count(*) from search_index_queue where entity_id = cast(:id as uuid)")
                .param("id", id).query(Long.class).single());
        assertThat(queued[0]).isEqualTo(1);
    }
}
