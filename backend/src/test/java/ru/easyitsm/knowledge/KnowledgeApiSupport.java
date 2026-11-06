package ru.easyitsm.knowledge;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.easyitsm.platform.id.Ids;

/** Пользователи и короткие вызовы API базы знаний для тестов. */
public final class KnowledgeApiSupport {

    public static final String DEMO = "01930000-0000-7000-8000-000000000001";
    public static final String OTHER = "01930000-0000-7000-8000-000000000002";
    public static final String API = "/api/v1/knowledge/articles";

    /** Пользователь теста: id, арендатор и роли через заголовки входа для разработки. */
    public record User(String id, String tenant, String roles) {

        public static User of(String roles) {
            return new User(Ids.newId().toString(), DEMO, roles);
        }

        public User in(String tenant) {
            return new User(id, tenant, roles);
        }

        public MockHttpServletRequestBuilder sign(MockHttpServletRequestBuilder req) {
            return req.header("X-Dev-User", id).header("X-Dev-Tenant", tenant).header("X-Dev-Roles", roles);
        }
    }

    private final MockMvc mvc;

    public KnowledgeApiSupport(MockMvc mvc) {
        this.mvc = mvc;
    }

    public ResultActions perform(User u, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(u.sign(req));
    }

    public ResultActions json(User u, MockHttpServletRequestBuilder req, String body) throws Exception {
        return mvc.perform(u.sign(req).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Создаёт черновик и возвращает id. */
    public String create(User u, String title, String body, String audience) throws Exception {
        String json = """
                {"title":%s,"body":%s,"audience":"%s"}""".formatted(q(title), q(body), audience);
        String res = json(u, post(API), json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.id");
    }

    public long etag(User u, String id) throws Exception {
        String res = perform(u, get(API + "/" + id).param("version", "current")).andReturn().getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(res, "$.version")).longValue();
    }

    public ResultActions action(User u, String id, String action, String body) throws Exception {
        long v = etag(u, id);
        return mvc.perform(u.sign(post(API + "/" + id + "/" + action)).header("If-Match", "\"" + v + "\"")
                .contentType(MediaType.APPLICATION_JSON).content(body == null ? "{}" : body));
    }

    public ResultActions edit(User u, String id, String body) throws Exception {
        long v = etag(u, id);
        return mvc.perform(u.sign(patch(API + "/" + id)).header("If-Match", "\"" + v + "\"")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Черновик автора, проверка и публикация другим редактором знаний. */
    public String published(User author, User knw, String title, String body, String audience) throws Exception {
        String id = create(author, title, body, audience);
        action(author, id, "submit", "{\"confirmNoSecrets\":true}").andExpect(status().isOk());
        action(knw, id, "publish", "{}").andExpect(status().isOk());
        return id;
    }

    /** Инцидент от заявителя, взятый в работу агентом. Возвращает id заявки. */
    public String ticketInProgress(User requester, User agent, String title) throws Exception {
        String res = json(requester, post("/api/v1/tickets"),
                "{\"type\":\"incident\",\"title\":%s,\"description\":\"Описание\"}".formatted(q(title)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(res, "$.id");
        mvc.perform(agent.sign(post("/api/v1/tickets/" + id + "/take")).header("If-Match", ticketEtag(agent, id)))
                .andExpect(status().isOk());
        return id;
    }

    /** Решает заявку, взятую агентом. */
    public void resolveTicket(User agent, String id) throws Exception {
        mvc.perform(agent.sign(post("/api/v1/tickets/" + id + "/transitions")).header("If-Match", ticketEtag(agent, id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"resolved\",\"resolutionCode\":\"fixed\",\"resolutionText\":\"Создали профиль\"}"))
                .andExpect(status().isOk());
    }

    private String ticketEtag(User u, String id) throws Exception {
        return perform(u, get("/api/v1/tickets/" + id)).andExpect(status().isOk()).andReturn().getResponse()
                .getHeader("ETag");
    }

    public static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    public static String uniq(String s) {
        return s + " " + UUID.randomUUID().toString().substring(0, 8);
    }
}
