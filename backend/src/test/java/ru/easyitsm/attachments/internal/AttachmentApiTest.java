package ru.easyitsm.attachments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.easyitsm.attachments.api.AttachmentIntake;
import ru.easyitsm.attachments.api.IncomingFile;
import ru.easyitsm.knowledge.KnowledgeApiSupport;
import ru.easyitsm.knowledge.KnowledgeApiSupport.User;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Вложения на настоящем S3 (MinIO или moto из {@code EASYITSM_TEST_S3_ENDPOINT}): загрузка по подписанной
 * ссылке, карантин, проверка, скачивание, права по заявке, внутренние файлы, удаление, лимиты.
 */
@SpringBootTest(properties = "easyitsm.attachments.uploads-per-minute=1000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "EASYITSM_TEST_S3_ENDPOINT", matches = ".+")
class AttachmentApiTest {

    static final String DEMO = KnowledgeApiSupport.DEMO;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AttachmentJobs jobs;

    @Autowired
    AttachmentIntake intake;

    User requester;
    User stranger;
    User agent;
    User otherAgent;
    String ticket;

    @BeforeEach
    void setUp() throws Exception {
        requester = User.of("REQ");
        stranger = User.of("REQ");
        agent = user("AGT");
        otherAgent = user("AGT");
        String group = group(agent);
        String res = mvc.perform(requester.sign(post("/api/v1/tickets")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"incident\",\"title\":\"Скрин ошибки\",\"description\":\"Во вложении\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        ticket = JsonPath.read(res, "$.id");
        TenantContext.runAs(UUID.fromString(DEMO), () -> jdbc.sql(
                        "update ticket set group_id = cast(:g as uuid) where id = cast(:id as uuid)")
                .param("g", group).param("id", ticket).update());
    }

    @Test
    void uploadScanAndDownloadByPresignedLinks() throws Exception {
        byte[] png = png();
        String id = upload(requester, "экран ошибки.png", png, "image/png", "public");

        // До проверки файл в карантине: скачать нельзя.
        mvc.perform(requester.sign(get("/api/v1/attachments/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("scanning"));
        mvc.perform(requester.sign(get("/api/v1/attachments/" + id + "/download")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("attachment_scanning"));

        scanAll();
        mvc.perform(requester.sign(get("/api/v1/attachments/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("clean"))
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.previewable").value(true))
                .andExpect(jsonPath("$.sha256").isString());

        String location = mvc.perform(agent.sign(get("/api/v1/attachments/" + id + "/download")))
                .andExpect(status().isSeeOther()).andReturn().getResponse().getHeader("Location");
        HttpResponse<byte[]> file = HTTP.send(HttpRequest.newBuilder(URI.create(location)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).isEqualTo(png);
        assertThat(file.headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment;");
        assertThat(file.headers().firstValue("Content-Type").orElse("")).isEqualTo("application/octet-stream");

        String preview = mvc.perform(requester.sign(get("/api/v1/attachments/" + id + "/preview")))
                .andExpect(status().isSeeOther()).andReturn().getResponse().getHeader("Location");
        HttpResponse<byte[]> inline = HTTP.send(HttpRequest.newBuilder(URI.create(preview)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(inline.headers().firstValue("Content-Type").orElse("")).isEqualTo("image/png");
        assertThat(inline.headers().firstValue("Content-Disposition").orElse("")).startsWith("inline;");

        List<String> listed = JsonPath.read(mvc.perform(requester.sign(get("/api/v1/attachments")
                .param("ownerType", "ticket").param("ownerId", ticket))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$[*].id");
        assertThat(listed).containsExactly(id);
    }

    @Test
    void accessFollowsTicketVisibility() throws Exception {
        String id = upload(requester, "лог.txt", "ошибка 500".getBytes(StandardCharsets.UTF_8), "text/plain", "public");
        scanAll();
        mvc.perform(stranger.sign(get("/api/v1/attachments/" + id))).andExpect(status().isNotFound());
        mvc.perform(otherAgent.sign(get("/api/v1/attachments/" + id + "/download"))).andExpect(status().isNotFound());
        mvc.perform(stranger.sign(get("/api/v1/attachments").param("ownerType", "ticket").param("ownerId", ticket)))
                .andExpect(status().isNotFound());
        mvc.perform(stranger.sign(post("/api/v1/attachments")).contentType(MediaType.APPLICATION_JSON)
                        .content(newUpload("x.txt", 3, "text/plain", "public")))
                .andExpect(status().isNotFound());
        // Предпросмотр только для картинок.
        mvc.perform(requester.sign(get("/api/v1/attachments/" + id + "/preview")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("preview_unavailable"));
    }

    @Test
    void internalFilesOnlyForSupport() throws Exception {
        mvc.perform(requester.sign(post("/api/v1/attachments")).contentType(MediaType.APPLICATION_JSON)
                        .content(newUpload("x.txt", 3, "text/plain", "internal")))
                .andExpect(status().isForbidden());
        String id = upload(agent, "дамп конфигурации.txt", "секретов нет".getBytes(StandardCharsets.UTF_8),
                "text/plain", "internal");
        scanAll();
        mvc.perform(agent.sign(get("/api/v1/attachments/" + id))).andExpect(status().isOk());
        mvc.perform(requester.sign(get("/api/v1/attachments/" + id))).andExpect(status().isNotFound());
        List<String> listed = JsonPath.read(mvc.perform(requester.sign(get("/api/v1/attachments")
                .param("ownerType", "ticket").param("ownerId", ticket))).andReturn().getResponse()
                .getContentAsString(), "$[*].id");
        assertThat(listed).doesNotContain(id);
    }

    @Test
    void executableDisguisedAsImageGoesToQuarantine() throws Exception {
        byte[] exe = "MZ\u0090\u0000 not really an image".getBytes(StandardCharsets.ISO_8859_1);
        String id = upload(requester, "котик.png", exe, "image/png", "public");
        scanAll();
        mvc.perform(requester.sign(get("/api/v1/attachments/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("blocked"))
                .andExpect(jsonPath("$.verdict").value("executable:application/x-msdownload"));
        mvc.perform(requester.sign(get("/api/v1/attachments/" + id + "/download")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("attachment_quarantined"));

        User sec = User.of("SEC");
        String q = mvc.perform(sec.sign(get("/api/v1/attachments/quarantine").param("limit", "100")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(q, "$.items[*].id")).contains(id);
        mvc.perform(requester.sign(get("/api/v1/attachments/quarantine"))).andExpect(status().isForbidden());
    }

    @Test
    void limitsAreCheckedBeforeUpload() throws Exception {
        mvc.perform(requester.sign(post("/api/v1/attachments")).contentType(MediaType.APPLICATION_JSON)
                        .content(newUpload("большой.iso.zip", 101L * 1024 * 1024, "application/zip", "public")))
                .andExpect(status().isContentTooLarge()).andExpect(jsonPath("$.code").value("file_too_large"));
        mvc.perform(requester.sign(post("/api/v1/attachments")).contentType(MediaType.APPLICATION_JSON)
                        .content(newUpload("setup.exe", 10, "application/octet-stream", "public")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("file_type_blocked"));
        // Заявленный размер не совпал с загруженным: файл не принимается.
        String res = mvc.perform(requester.sign(post("/api/v1/attachments")).contentType(MediaType.APPLICATION_JSON)
                        .content(newUpload("a.txt", 3, "text/plain", "public")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(res, "$.attachment.id");
        mvc.perform(requester.sign(post("/api/v1/attachments/" + id + "/complete")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("upload_missing"));
    }

    @Test
    void uploaderDeletesOwnFile() throws Exception {
        String id = upload(requester, "лишнее.txt", "abc".getBytes(), "text/plain", "public");
        scanAll();
        String location = mvc.perform(requester.sign(get("/api/v1/attachments/" + id + "/download")))
                .andExpect(status().isSeeOther()).andReturn().getResponse().getHeader("Location");
        mvc.perform(stranger.sign(delete("/api/v1/attachments/" + id))).andExpect(status().isNotFound());
        mvc.perform(requester.sign(delete("/api/v1/attachments/" + id))).andExpect(status().isNoContent());
        mvc.perform(requester.sign(get("/api/v1/attachments/" + id))).andExpect(status().isNotFound());
        // Объект стёрт из хранилища: старая подписанная ссылка больше ничего не отдаёт.
        HttpResponse<byte[]> gone = HTTP.send(HttpRequest.newBuilder(URI.create(location)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        // S3 отвечает 403 или 404 в зависимости от права на список объектов.
        assertThat(gone.statusCode()).isIn(403, 404);
    }

    @Test
    void mailAttachmentGoesThroughQuarantine() throws Exception {
        UUID owner = UUID.fromString(ticket);
        UUID sender = UUID.fromString(requester.id());
        AttachmentIntake.Accepted[] got = new AttachmentIntake.Accepted[2];
        TenantContext.runAs(UUID.fromString(DEMO), () -> {
            got[0] = intake.accept(IncomingFile.ticketMail(owner, "../../лог\u202e.txt", "text/plain",
                    "строка лога".getBytes(StandardCharsets.UTF_8), sender));
            got[1] = intake.accept(IncomingFile.ticketMail(owner, "огромный.bin", "application/octet-stream",
                    new byte[101 * 1024 * 1024], sender));
        });
        assertThat(got[0].status()).isEqualTo("scanning");
        assertThat(got[1].status()).isEqualTo("blocked");
        assertThat(got[1].reason()).isEqualTo("file_too_large");

        scanAll();
        mvc.perform(requester.sign(get("/api/v1/attachments/" + got[0].id()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("clean"))
                .andExpect(jsonPath("$.fileName").value("лог.txt"))
                .andExpect(jsonPath("$.canDelete").value(true));
        String location = mvc.perform(requester.sign(get("/api/v1/attachments/" + got[0].id() + "/download")))
                .andExpect(status().isSeeOther()).andReturn().getResponse().getHeader("Location");
        HttpResponse<String> file = HTTP.send(HttpRequest.newBuilder(URI.create(location)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(file.body()).isEqualTo("строка лога");
        mvc.perform(agent.sign(get("/api/v1/attachments/" + got[1].id()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("blocked"))
                .andExpect(jsonPath("$.verdict").value("file_too_large"));
    }

    // ---------------------------------------------------------------- помощники

    /** Проверка карантина сейчас, не дожидаясь расписания worker. */
    void scanAll() {
        while (jobs.runScans() > 0) {
            // до пустой очереди
        }
    }

    /** Полный путь браузера: ссылка, PUT в S3, подтверждение. Возвращает id файла. */
    String upload(User u, String name, byte[] content, String type, String visibility) throws Exception {
        String res = mvc.perform(u.sign(post("/api/v1/attachments")).contentType(MediaType.APPLICATION_JSON)
                        .content(newUpload(name, content.length, type, visibility)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(res, "$.attachment.id");
        String url = JsonPath.read(res, "$.upload.url");
        Map<String, String> headers = JsonPath.read(res, "$.upload.headers");
        HttpRequest.Builder put = HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(content));
        headers.forEach(put::header);
        HttpResponse<String> r = HTTP.send(put.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        mvc.perform(u.sign(post("/api/v1/attachments/" + id + "/complete"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("scanning"));
        return id;
    }

    String newUpload(String name, long size, String type, String visibility) {
        return """
                {"ownerType":"ticket","ownerId":"%s","fileName":%s,"size":%d,"contentType":"%s","visibility":"%s"}"""
                .formatted(ticket, KnowledgeApiSupport.q(name), size, type, visibility);
    }

    User user(String roles) {
        User u = User.of(roles);
        TenantContext.runAs(UUID.fromString(DEMO), () -> jdbc.sql("""
                        insert into user_account (id, tenant_id, login, display_name, created_at, updated_at, version)
                        values (cast(:id as uuid), cast(:t as uuid), :login, 'Тест', now(), now(), 0)""")
                .param("id", u.id()).param("t", DEMO).param("login", "a" + u.id().replace("-", "")).update());
        return u;
    }

    String group(User... members) {
        String group = Ids.newId().toString();
        TenantContext.runAs(UUID.fromString(DEMO), () -> {
            jdbc.sql("""
                            insert into user_group (id, tenant_id, name, created_at, updated_at, version)
                            values (cast(:id as uuid), cast(:t as uuid), :name, now(), now(), 0)""")
                    .param("id", group).param("t", DEMO).param("name", "Группа " + group).update();
            for (User m : members) {
                jdbc.sql("insert into group_member (tenant_id, group_id, user_id)"
                                + " values (cast(:t as uuid), cast(:g as uuid), cast(:u as uuid))")
                        .param("t", DEMO).param("g", group).param("u", m.id()).update();
            }
        });
        return group;
    }

    /** Минимальный PNG 1×1. */
    static byte[] png() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    }
}
