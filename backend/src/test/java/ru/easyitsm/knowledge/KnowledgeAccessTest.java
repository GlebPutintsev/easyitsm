package ru.easyitsm.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ru.easyitsm.knowledge.KnowledgeApiSupport.API;
import static ru.easyitsm.knowledge.KnowledgeApiSupport.uniq;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.easyitsm.knowledge.KnowledgeApiSupport.User;

/**
 * Видимость по аудитории (KB-04, ACC-02, NFR-16): карточка статьи и списки дают один и тот же ответ,
 * потому что правило записано и в коде, и в SQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeAccessTest {

    static final User AGENT = User.of("AGT");
    static final User KNW = User.of("KNW");
    static final User REQ = User.of("REQ");
    static final User EXTERNAL = User.of("REQ,EXT");
    static final UUID GROUP = UUID.randomUUID();
    static final User MEMBER = User.of("REQ,GROUP_" + GROUP);
    static final User AUDITOR = User.of("AUD");

    @Autowired
    MockMvc mvc;
    KnowledgeApiSupport kb;

    @BeforeEach
    void setUp() {
        kb = new KnowledgeApiSupport(mvc);
    }

    @Test
    void audienceAll() throws Exception {
        String id = kb.published(AGENT, KNW, uniq("Для всех"), "Текст", "all");
        expectVisible(id, REQ, EXTERNAL, MEMBER, AGENT);
    }

    @Test
    void audienceEmployees() throws Exception {
        String id = kb.published(AGENT, KNW, uniq("Сотрудникам"), "Текст", "employees");
        expectVisible(id, REQ, MEMBER, AGENT);
        expectHidden(id, EXTERNAL);
    }

    @Test
    void audienceAgents() throws Exception {
        String id = kb.published(AGENT, KNW, uniq("Агентам"), "Текст", "agents");
        expectVisible(id, AGENT, AUDITOR, User.of("ADM"));
        expectHidden(id, REQ, EXTERNAL, MEMBER);
    }

    @Test
    void audienceGroup() throws Exception {
        String id = kb.create(AGENT, uniq("Группе"), "Текст", "all");
        kb.edit(AGENT, id, "{\"audience\":\"group\"}").andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("audience_group_required"));
        kb.edit(AGENT, id, "{\"audience\":\"group\",\"audienceGroupId\":\"%s\"}".formatted(GROUP))
                .andExpect(status().isOk());
        kb.action(AGENT, id, "submit", "{}").andExpect(status().isOk());
        kb.action(KNW, id, "publish", "{}").andExpect(status().isOk());
        expectVisible(id, MEMBER, KNW);
        expectHidden(id, REQ, EXTERNAL, User.of("AGT"));
    }

    @Test
    void draftsAreVisibleOnlyToAuthorAndKnowledgeRoles() throws Exception {
        String id = kb.create(AGENT, uniq("Черновик"), "Текст", "all");
        kb.perform(AGENT, get(API + "/" + id)).andExpect(status().isOk());
        kb.perform(KNW, get(API + "/" + id)).andExpect(status().isOk());
        kb.perform(AUDITOR, get(API + "/" + id)).andExpect(status().isOk());
        kb.perform(User.of("AGT"), get(API + "/" + id)).andExpect(status().isNotFound());
        kb.perform(REQ, get(API + "/" + id)).andExpect(status().isNotFound());

        assertThat(workspaceIds(User.of("AGT"))).doesNotContain(id);
        assertThat(workspaceIds(KNW)).contains(id);
    }

    @Test
    void auditorCannotChangeAnything() throws Exception {
        String id = kb.published(AGENT, KNW, uniq("Аудит"), "Текст", "all");
        kb.edit(AUDITOR, id, "{\"body\":\"x\"}").andExpect(status().isForbidden());
        kb.action(AUDITOR, id, "archive", "{}").andExpect(status().isForbidden());
    }

    private void expectVisible(String id, User... users) throws Exception {
        for (User u : users) {
            kb.perform(u, get(API + "/" + id)).andExpect(status().isOk());
            assertThat(portalIds(u)).as("список для %s", u.roles()).contains(id);
        }
    }

    private void expectHidden(String id, User... users) throws Exception {
        for (User u : users) {
            kb.perform(u, get(API + "/" + id)).andExpect(status().isNotFound());
            assertThat(portalIds(u)).as("список для %s", u.roles()).doesNotContain(id);
        }
    }

    private List<String> portalIds(User u) throws Exception {
        String res = kb.perform(u, get(API).param("limit", "200")).andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.items[*].id");
    }

    private List<String> workspaceIds(User u) throws Exception {
        String res = kb.perform(u, get(API).param("view", "workspace").param("limit", "200")).andReturn().getResponse()
                .getContentAsString();
        return JsonPath.read(res, "$.items[*].id");
    }
}
