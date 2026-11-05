package ru.easyitsm.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ru.easyitsm.knowledge.KnowledgeApiSupport.API;
import static ru.easyitsm.knowledge.KnowledgeApiSupport.OTHER;
import static ru.easyitsm.knowledge.KnowledgeApiSupport.uniq;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import ru.easyitsm.knowledge.KnowledgeApiSupport.User;
import ru.easyitsm.knowledge.events.ArticleCreatedFromTicket;
import ru.easyitsm.knowledge.events.ArticlePublished;
import ru.easyitsm.knowledge.events.ArticleSubmittedForReview;

/** Статьи: жизненный цикл, версии, четыре глаза, секреты, статья из заявки, поиск, оценки. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
class KnowledgeApiTest {

    static final User AGENT = User.of("AGT");
    static final User KNW = User.of("KNW");
    static final User KNW2 = User.of("KNW");
    static final User REQ = User.of("REQ");

    @Autowired
    MockMvc mvc;
    @Autowired
    ApplicationEvents events;
    KnowledgeApiSupport kb;

    @BeforeEach
    void setUp() {
        kb = new KnowledgeApiSupport(mvc);
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get(API)).andExpect(status().isUnauthorized());
    }

    @Test
    void requesterCannotCreateOrOpenWorkspace() throws Exception {
        kb.json(REQ, post(API), "{\"title\":\"Статья\"}").andExpect(status().isForbidden());
        kb.perform(REQ, get(API).param("view", "workspace")).andExpect(status().isForbidden());
    }

    @Test
    void templateFillsBody() throws Exception {
        kb.json(AGENT, post(API), "{\"title\":\"Как сменить пароль\",\"template\":\"howto\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.key").value(org.hamcrest.Matchers.startsWith("KB-")))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("## Шаги")))
                .andExpect(jsonPath("$.audience").value("employees"))
                .andExpect(jsonPath("$.permissions.edit").value(true))
                .andExpect(jsonPath("$.permissions.publish").value(false));
        kb.perform(AGENT, get("/api/v1/knowledge/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'solution')]").exists());
    }

    @Test
    void lifecycleDraftReviewPublished() throws Exception {
        String title = uniq("VPN не подключается");
        String id = kb.create(AGENT, title, "Перезапустите клиент", "all");

        // Без If-Match правка запрещена, черновик правится на месте без новой версии.
        kb.json(AGENT, patch(API + "/" + id), "{\"body\":\"x\"}").andExpect(status().isPreconditionRequired());
        kb.edit(AGENT, id, "{\"body\":\"Перезапустите клиент VPN\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersionNo").value(1));

        // Черновик не виден заявителю.
        kb.perform(REQ, get(API + "/" + id)).andExpect(status().isNotFound());

        kb.action(AGENT, id, "submit", "{\"comment\":\"Проверьте, пожалуйста\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("review"));
        assertThat(events.stream(ArticleSubmittedForReview.class).filter(e -> e.articleId().toString().equals(id)))
                .hasSize(1);

        // На проверке правка закрыта, агент опубликовать не может.
        kb.edit(AGENT, id, "{\"body\":\"ещё\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("article_in_review"));
        kb.action(AGENT, id, "publish", "{}").andExpect(status().isForbidden());

        kb.action(KNW, id, "publish", "{\"notes\":\"Первая версия\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"))
                .andExpect(jsonPath("$.publishedVersionNo").value(1))
                .andExpect(jsonPath("$.reviewAt").exists());
        assertThat(events.stream(ArticlePublished.class).filter(e -> e.articleId().toString().equals(id))).hasSize(1);

        kb.perform(REQ, get(API + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("Перезапустите клиент VPN"))
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.permissions.edit").value(false));
        kb.perform(REQ, get(API + "/" + id).param("version", "current")).andExpect(status().isNotFound());
        kb.perform(REQ, get(API + "/" + id + "/versions")).andExpect(status().isForbidden());
    }

    @Test
    void authorCannotPublishOwnArticle() throws Exception {
        String id = kb.create(KNW, uniq("Своя статья"), "Текст", "all");
        kb.action(KNW, id, "publish", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invalid_transition"));
        kb.action(KNW, id, "submit", "{}").andExpect(status().isOk());
        kb.action(KNW, id, "publish", "{}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("four_eyes"));
        kb.action(KNW2, id, "publish", "{}").andExpect(status().isOk());
    }

    @Test
    void rejectReturnsToDraftWithComment() throws Exception {
        String id = kb.create(AGENT, uniq("Черновик"), "Текст", "all");
        kb.action(AGENT, id, "submit", "{}").andExpect(status().isOk());
        kb.action(KNW, id, "reject", "{}").andExpect(status().isBadRequest());
        kb.action(KNW, id, "reject", "{\"comment\":\"Добавьте скриншоты\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.reviewComment").value("Добавьте скриншоты"));
        kb.edit(AGENT, id, "{\"body\":\"Текст со скриншотами\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersionNo").value(1));
    }

    @Test
    void editingPublishedCreatesNewVersionAndReadersKeepOldOne() throws Exception {
        String id = kb.published(AGENT, KNW, uniq("Принтер"), "Старый текст", "all");

        kb.edit(AGENT, id, "{\"body\":\"Новый текст\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.currentVersionNo").value(2))
                .andExpect(jsonPath("$.publishedVersionNo").value(1));
        kb.edit(AGENT, id, "{\"body\":\"Новый текст, правка\"}").andExpect(jsonPath("$.currentVersionNo").value(2));

        kb.perform(REQ, get(API + "/" + id)).andExpect(jsonPath("$.body").value("Старый текст"));
        kb.perform(AGENT, get(API + "/" + id).param("version", "current"))
                .andExpect(jsonPath("$.body").value("Новый текст, правка"));

        kb.perform(AGENT, get(API + "/" + id + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].versionNo").value(2))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[1].published").value(true))
                .andExpect(jsonPath("$[1].notes").value(""));
        kb.perform(AGENT, get(API + "/" + id + "/versions/1")).andExpect(jsonPath("$.body").value("Старый текст"));

        long v = kb.etag(AGENT, id);
        kb.perform(AGENT, post(API + "/" + id + "/versions/1/restore").header("If-Match", "\"" + v + "\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersionNo").value(3))
                .andExpect(jsonPath("$.body").value("Старый текст"));
        kb.perform(AGENT, get(API + "/" + id + "/versions/3")).andExpect(jsonPath("$.restoredFrom").value(1));
    }

    @Test
    void secretsBlockSubmitUntilConfirmed() throws Exception {
        String id = kb.create(AGENT, uniq("Доступ к серверу"), "Логин admin\nпароль: Qwerty12345", "agents");
        kb.action(AGENT, id, "submit", "{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("secrets_detected"))
                .andExpect(jsonPath("$.findings[0].kind").value("password"))
                .andExpect(jsonPath("$.findings[0].line").value(3))
                .andExpect(jsonPath("$.trace_id").exists());
        kb.action(AGENT, id, "submit", "{\"confirmNoSecrets\":true}").andExpect(status().isOk());
    }

    @Test
    void articleFromResolvedTicket() throws Exception {
        UUID ticket = UUID.randomUUID();
        String body = """
                {"ticketId":"%s","ticketRef":"INC-000123","title":"Outlook не открывается",
                 "symptoms":"Outlook зависает при запуске","cause":"Повреждён профиль","solution":"Создать новый профиль"}"""
                .formatted(ticket);
        kb.json(AGENT, post(API + "/from-ticket"), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.audience").value("agents"))
                .andExpect(jsonPath("$.sourceTicketId").value(ticket.toString()))
                .andExpect(jsonPath("$.sourceTicketRef").value("INC-000123"))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("## Решение\n\nСоздать новый профиль")));
        assertThat(events.stream(ArticleCreatedFromTicket.class).filter(e -> e.ticketId().equals(ticket))).hasSize(1);

        kb.json(AGENT, post(API + "/from-ticket"),
                "{\"ticketId\":\"%s\",\"title\":\"Без решения\"}".formatted(ticket)).andExpect(status().isBadRequest());
        kb.json(REQ, post(API + "/from-ticket"), body).andExpect(status().isForbidden());
    }

    @Test
    void searchUsesRussianMorphologyAndSuggestions() throws Exception {
        String marker = "Зюзякин" + UUID.randomUUID().toString().substring(0, 4).replaceAll("[0-9]", "з");
        String id = kb.published(AGENT, KNW, "Настройка сетевых принтеров " + marker,
                "Как подключить сетевой принтер в бухгалтерии " + marker, "all");

        String res = kb.perform(REQ, get(API).param("q", "принтер " + marker)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(res, "$.items[*].id")).contains(id);
        assertThat(JsonPath.<String>read(res, "$.items[0].snippet")).doesNotContain("#");

        // Поиск при наборе: начало слова.
        res = kb.perform(REQ, get(API).param("q", marker.substring(0, 6).toLowerCase() + " принт"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(res, "$.items[*].id")).contains(id);

        res = kb.perform(REQ, get("/api/v1/knowledge/suggestions")
                        .param("text", "Не печатает принтер, сообщает об ошибке " + marker))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(res, "$[*].id")).contains(id);
    }

    @Test
    void archiveHidesFromReadersAndUnarchiveReturnsDraft() throws Exception {
        String id = kb.published(AGENT, KNW, uniq("Устаревшая статья"), "Текст", "all");
        kb.action(AGENT, id, "archive", "{}").andExpect(status().isForbidden());
        kb.action(KNW, id, "archive", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("archived"));
        kb.perform(REQ, get(API + "/" + id)).andExpect(status().isNotFound());
        kb.edit(AGENT, id, "{\"body\":\"x\"}").andExpect(status().isConflict());
        kb.action(KNW, id, "unarchive", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.publishedVersionNo").doesNotExist());
    }

    @Test
    void reviewCanBeRequestedAndConfirmed() throws Exception {
        String id = kb.published(AGENT, KNW, uniq("Пересмотр"), "Текст", "all");
        kb.action(KNW, id, "request-review", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("review_due"));
        kb.perform(REQ, get(API + "/" + id)).andExpect(status().isOk());
        kb.action(KNW, id, "confirm-review", "{\"reviewAt\":\"2020-01-01T00:00:00Z\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_review_at"));
        kb.action(KNW, id, "confirm-review", "{\"reviewAt\":\"2099-01-01T00:00:00Z\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"))
                .andExpect(jsonPath("$.reviewAt").value("2099-01-01T00:00:00Z"));
    }

    @Test
    void feedbackIsOnePerUser() throws Exception {
        String id = kb.published(AGENT, KNW, uniq("Оценка"), "Текст", "all");
        kb.json(REQ, put(API + "/" + id + "/feedback"), "{\"helpful\":false,\"comment\":\"Не помогло\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notHelpful").value(1))
                .andExpect(jsonPath("$.mine").value(false));
        kb.json(REQ, put(API + "/" + id + "/feedback"), "{\"helpful\":true}")
                .andExpect(jsonPath("$.helpful").value(1))
                .andExpect(jsonPath("$.notHelpful").value(0))
                .andExpect(jsonPath("$.mine").value(true));
        String draft = kb.create(AGENT, uniq("Черновик"), "Текст", "all");
        kb.json(AGENT, put(API + "/" + draft + "/feedback"), "{\"helpful\":true}").andExpect(status().isConflict());
    }

    @Test
    void anotherTenantGets404() throws Exception {
        String id = kb.published(AGENT, KNW, uniq("Чужая"), "Текст", "all");
        kb.perform(KNW.in(OTHER), get(API + "/" + id)).andExpect(status().isNotFound());
        kb.perform(KNW.in(OTHER), get(API + "/" + id + "/versions")).andExpect(status().isNotFound());
        mvc.perform(KNW.in(OTHER).sign(patch(API + "/" + id)).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void workspacePagesWithCursorAndFilters() throws Exception {
        User author = User.of("AGT").in(OTHER);
        for (int i = 0; i < 3; i++) {
            kb.create(author, "Статья " + i, "Текст", "agents");
        }
        String page1 = kb.perform(author, get(API).param("view", "workspace").param("mine", "true")
                        .param("status", "draft").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");
        kb.perform(author, get(API).param("view", "workspace").param("mine", "true").param("limit", "2")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        kb.perform(author, get(API).param("view", "workspace").param("status", "nope"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void categoriesAreManagedByKnowledgeEditors() throws Exception {
        String name = uniq("Почта");
        kb.json(AGENT, post("/api/v1/knowledge/categories"), "{\"name\":\"x\"}").andExpect(status().isForbidden());
        String res = kb.json(KNW, post("/api/v1/knowledge/categories"), "{\"name\":\"%s\"}".formatted(name))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String cat = JsonPath.read(res, "$.id");
        kb.json(AGENT, post(API), "{\"title\":\"В категории\",\"categoryId\":\"%s\"}".formatted(cat))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.categoryId").value(cat));
        kb.json(AGENT, post(API), "{\"title\":\"Нет категории\",\"categoryId\":\"%s\"}".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_category"));
        kb.perform(KNW.in(OTHER), get("/api/v1/knowledge/categories"))
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(cat)).doesNotExist());
    }
}
