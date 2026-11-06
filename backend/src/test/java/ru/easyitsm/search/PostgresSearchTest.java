package ru.easyitsm.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.easyitsm.knowledge.KnowledgeApiSupport.User;

/**
 * Глобальный поиск без OpenSearch (пилот): резервный путь PostgreSQL, русская морфология и права на заявки по
 * группам (#24) и статьи по аудиториям.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostgresSearchTest {

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
    String otherGroup;
    String word;

    @BeforeEach
    void setUp() {
        s = new SearchApiSupport(mvc, jdbc);
        requester = s.user("REQ");
        stranger = s.user("REQ");
        agent = s.user("AGT");
        otherAgent = s.user("AGT");
        group = s.group(agent);
        otherGroup = s.group(otherAgent);
        word = SearchApiSupport.marker();
    }

    @Test
    void findsWordFormsAndRespectsTicketVisibility() throws Exception {
        String id = s.ticket(requester, "Не печатает принтер " + word, "Принтер на третьем этаже", group);

        // «принтеры» находит «принтер»: русская морфология
        String mine = s.search(requester, "принтеры " + word, "ticket");
        assertThat(SearchApiSupport.ids(mine, "ticket")).containsExactly(id);
        assertThat((String) JsonPath.read(mine, "$.engine")).isEqualTo("postgres");
        assertThat((Boolean) JsonPath.read(mine, "$.degraded")).isFalse();

        assertThat(SearchApiSupport.ids(s.search(agent, "печатать " + word, "ticket"), "ticket")).containsExactly(id);
        assertThat(SearchApiSupport.ids(s.search(stranger, "принтер " + word, "ticket"), "ticket")).isEmpty();
        assertThat(SearchApiSupport.ids(s.search(otherAgent, "принтер " + word, "ticket"), "ticket")).isEmpty();
    }

    @Test
    void findsByNumberAndHighlightsSnippet() throws Exception {
        String id = s.ticket(requester, "Сбой почты " + word, "Письма не уходят наружу, " + word + " в журнале", group);
        String number = JsonPath.read(mvc.perform(requester.sign(get("/api/v1/tickets/" + id)))
                .andReturn().getResponse().getContentAsString(), "$.number");

        String json = s.search(requester, number, "ticket");
        assertThat(SearchApiSupport.ids(json, "ticket")).containsExactly(id);

        String byText = s.search(requester, "письмо " + word, "ticket");
        List<Boolean> marks = JsonPath.read(byText, "$.groups[0].items[0].snippet[*].hl");
        assertThat(marks).contains(true);
    }

    @Test
    void confidentialTicketOnlyForSecurityGroup() throws Exception {
        User security = s.user("AGT");
        String securityGroup = s.group(security);
        String id = s.ticket(requester, "Утечка данных " + word, "Подозрение", group);
        s.confidential(id, securityGroup);

        assertThat(SearchApiSupport.ids(s.search(agent, "утечка " + word, "ticket"), "ticket")).isEmpty();
        assertThat(SearchApiSupport.ids(s.search(security, "утечка " + word, "ticket"), "ticket")).containsExactly(id);
        // Заявитель — участник, видит свою конфиденциальную заявку.
        assertThat(SearchApiSupport.ids(s.search(requester, "утечка " + word, "ticket"), "ticket"))
                .containsExactly(id);
    }

    @Test
    void articlesByAudience() throws Exception {
        User knw = s.user("KNW");
        User author = s.user("AGT");
        String forAll = s.kb.published(author, knw, "Как подключить сетевой принтер " + word, "Откройте настройки "
                + word, "all");
        String forAgents = s.kb.published(author, knw, "Сброс очереди печати " + word, "Остановите службу "
                + word, "agents");

        assertThat(SearchApiSupport.ids(s.search(requester, word, "article"), "article")).containsExactly(forAll);
        assertThat(SearchApiSupport.ids(s.search(agent, word, "article"), "article"))
                .containsExactlyInAnyOrder(forAll, forAgents);
    }

    @Test
    void allKindsByDefaultAndValidation() throws Exception {
        String json = s.search(requester, "принтер " + word, null);
        List<String> kinds = JsonPath.read(json, "$.groups[*].kind");
        assertThat(kinds).containsExactly("ticket", "article", "service");

        mvc.perform(requester.sign(get("/api/v1/search").param("q", "a"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("query_too_short"));
        mvc.perform(requester.sign(get("/api/v1/search").param("q", "принтер").param("types", "cmdb")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/search").param("q", "принтер")).andExpect(status().isUnauthorized());
    }

    @Test
    void statusAndReindexForAdminOnly() throws Exception {
        User admin = s.user("ADM");
        mvc.perform(admin.sign(get("/api/v1/search/status"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.openSearchEnabled").value(false));
        mvc.perform(requester.sign(get("/api/v1/search/status"))).andExpect(status().isForbidden());
    }
}
