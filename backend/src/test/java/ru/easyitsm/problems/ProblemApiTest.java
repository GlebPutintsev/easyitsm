package ru.easyitsm.problems;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.problems.api.NewProblem;
import ru.easyitsm.problems.api.ProblemCommands;
import ru.easyitsm.problems.api.ProblemRef;

/** Проблемы (PRB-01…05): создание из инцидентов, анализ, известная ошибка, изменение, решение, повторы. */
class ProblemApiTest extends ProblemApiSupport {

    @Autowired
    ProblemCommands problemCommands;

    private String createProblem(String user, String roles, String title, String... incidents) throws Exception {
        String ids = String.join(",", java.util.Arrays.stream(incidents).map(i -> "\"" + i + "\"").toList());
        return body(perform(as(post("/api/v1/problems"), user, roles)
                .content("{\"title\":\"" + title + "\",\"description\":\"Описание\",\"groupId\":\"" + group
                        + "\",\"incidentIds\":[" + ids + "]}"))
                .andExpect(status().isCreated()));
    }

    @Test
    void problemFromIncidentsLinksThemAndCopiesTimeline() throws Exception {
        String inc1 = incident(uniq("Не открывается 1С"));
        String inc2 = incident(uniq("1С висит при входе"));
        String json = createProblem(agent, "AGT", "Зависания 1С", inc1, inc2);
        assertThat((String) read(json, "$.number")).startsWith("PRB-");
        assertThat((String) read(json, "$.source")).isEqualTo("incident");
        assertThat((List<String>) read(json, "$.incidents[*].id")).containsExactlyInAnyOrder(inc1, inc2);
        assertThat((List<?>) read(json, "$.analysis.timeline")).hasSize(2);
        assertThat((Boolean) read(json, "$.permissions.edit")).isTrue();
        assertThat((Boolean) read(json, "$.permissions.publishKnownError")).isFalse();
        String problemId = read(json, "$.id");

        // Связь видна и в заявках: инцидент вызван проблемой.
        String links = body(perform(as(get("/api/v1/tickets/" + inc1 + "/links"), agent, "AGT")));
        assertThat((List<String>) read(links, "$[*].kind")).contains("caused_by");
        assertThat((List<String>) read(links, "$[*].ticket.id")).contains(problemId);

        // По номеру тоже открывается.
        perform(as(get("/api/v1/problems/" + read(json, "$.number")), lead, "AGT,LEAD"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.permissions.publishKnownError").value(true));
    }

    @Test
    void createRequiresSupportAndVisibleIncidents() throws Exception {
        String inc = incident(uniq("Сбой почты"));
        perform(as(post("/api/v1/problems"), requester, "REQ").content("{\"title\":\"X\"}"))
                .andExpect(status().isForbidden());
        // Инцидент чужой группы агенту не виден — 404, как в заявках.
        perform(as(post("/api/v1/problems"), outsider, "AGT")
                .content("{\"title\":\"X\",\"incidentIds\":[\"" + inc + "\"]}"))
                .andExpect(status().isNotFound());
        String req = read(body(perform(as(post("/api/v1/tickets"), requester, "REQ")
                .content("{\"type\":\"request\",\"title\":\"Мышь\",\"description\":\"-\"}"))), "$.id");
        setGroup(req, group);
        perform(as(post("/api/v1/problems"), agent, "AGT")
                .content("{\"title\":\"X\",\"incidentIds\":[\"" + req + "\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("not_incident"));
        perform(as(post("/api/v1/problems"), agent, "AGT")
                .content("{\"title\":\"X\",\"groupId\":\"" + Ids.newId() + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_assignment"));
    }

    @Test
    void analysisNeedsIfMatchAndKnownCategories() throws Exception {
        String json = createProblem(agent, "AGT", uniq("Падает VPN"), incident(uniq("VPN рвётся")));
        String id = read(json, "$.id");
        String analysis = """
                {"rootCause":"Истёк сертификат шлюза","workaround":"Переподключиться через резервный шлюз",
                 "analysis":{"timeline":[{"at":"2026-11-01T10:00:00Z","text":"Обновили шлюз"}],
                   "whys":[{"question":"Почему рвётся VPN?","answer":"Шлюз отклоняет сессии"},
                           {"question":"Почему отклоняет?","answer":"Истёк сертификат"}],
                   "ishikawa":{"technology":["Сертификат","  "],"process":["Нет контроля сроков"]},
                   "conclusion":"Нужен мониторинг сроков сертификатов"},
                 "closeIncidents":false}""";
        perform(as(put("/api/v1/problems/" + id + "/analysis"), agent, "AGT").content(analysis))
                .andExpect(status().isPreconditionRequired());
        perform(as(put("/api/v1/problems/" + id + "/analysis"), agent, "AGT").header("If-Match", "\"7\"")
                .content(analysis)).andExpect(status().isPreconditionFailed());
        perform(as(put("/api/v1/problems/" + id + "/analysis"), agent, "AGT").header("If-Match", "\"1\"")
                .content(analysis))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.rootCause").value("Истёк сертификат шлюза"))
                .andExpect(jsonPath("$.analysis.whys.length()").value(2))
                .andExpect(jsonPath("$.analysis.ishikawa.technology.length()").value(1))
                .andExpect(jsonPath("$.analysis.timeline.length()").value(1))
                .andExpect(jsonPath("$.closeIncidents").value(false));
        perform(as(put("/api/v1/problems/" + id + "/analysis"), agent, "AGT").header("If-Match", "\"2\"")
                .content("{\"analysis\":{\"ishikawa\":{\"luck\":[\"x\"]}}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_ishikawa"));
        // Агент другой группы проблему не видит.
        perform(as(get("/api/v1/problems/" + id), outsider, "AGT")).andExpect(status().isNotFound());
        perform(as(put("/api/v1/problems/" + id + "/analysis"), outsider, "AGT").header("If-Match", "\"2\"")
                .content("{}")).andExpect(status().isNotFound());
        // Другой арендатор — тоже 404.
        perform(as(get("/api/v1/problems/" + id), agent, "AGT", OTHER)).andExpect(status().isNotFound());
    }

    @Test
    void knownErrorIsPublishedByManagerAndSuggestedForIncidents() throws Exception {
        String word = "Принтер" + UUID.randomUUID().toString().substring(0, 6);
        String json = createProblem(agent, "AGT", uniq("Не печатает " + word), incident(uniq("Не печатает " + word)));
        String id = read(json, "$.id");
        String ke = "{\"title\":\"" + word + " теряет очередь печати\",\"symptoms\":\"Задания исчезают\","
                + "\"workaround\":\"Перезапустить службу печати\"}";
        String saved = body(perform(as(put("/api/v1/problems/" + id + "/known-error"), agent, "AGT").content(ke))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.knownError.published").value(false)));
        int keVersion = read(saved, "$.knownError.version");

        String fresh = read(json, "$.id");
        String later = incident(word);
        // Черновик агенту не подсказывается.
        assertThat((List<?>) read(body(perform(as(get("/api/v1/known-errors").param("ticketId", later), agent, "AGT"))),
                "$")).isEmpty();
        perform(as(post("/api/v1/problems/" + fresh + "/known-error/publish"), agent, "AGT")
                .header("If-Match", "\"" + keVersion + "\"")).andExpect(status().isForbidden());
        perform(as(post("/api/v1/problems/" + fresh + "/known-error/publish"), lead, "AGT,LEAD")
                .header("If-Match", "\"" + keVersion + "\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.knownError.published").value(true));

        String found = body(perform(as(get("/api/v1/known-errors").param("ticketId", later), agent, "AGT"))
                .andExpect(status().isOk()));
        assertThat((List<String>) read(found, "$[*].problemId")).containsExactly(id);
        assertThat((String) read(found, "$[0].workaround")).isEqualTo("Перезапустить службу печати");
        assertThat((List<?>) read(body(perform(as(get("/api/v1/known-errors").param("q", word), agent, "AGT"))), "$"))
                .hasSize(1);
        perform(as(get("/api/v1/known-errors"), requester, "REQ")).andExpect(status().isForbidden());
        // Заявку, которую агент не видит, нельзя использовать для подбора.
        perform(as(get("/api/v1/known-errors").param("ticketId", later), outsider, "AGT"))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolvingProblemResolvesIncidentsInWork() throws Exception {
        String inWork = incident(uniq("Медленный терминал"));
        take(inWork, agent, "AGT");
        String waiting = incident(uniq("Терминал тормозит"));
        String json = createProblem(agent, "AGT", uniq("Терминальный сервер перегружен"), inWork, waiting);
        String id = read(json, "$.id");

        // До решения проблемы решать инциденты рано.
        perform(as(post("/api/v1/problems/" + id + "/resolve-incidents"), agent, "AGT").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("problem_not_resolved"));

        take(id, agent, "AGT");
        transition(id, agent, "AGT", "{\"to\":\"resolved\",\"resolutionCode\":\"fixed\","
                + "\"resolutionText\":\"Добавили узел в ферму\"}").andExpect(status().isOk());

        assertThat((String) read(ticket(inWork, agent, "AGT"), "$.status.category")).isEqualTo("resolved");
        assertThat((String) read(ticket(inWork, agent, "AGT"), "$.resolutionText"))
                .contains(read(json, "$.number").toString());
        // Без исполнителя инцидент остаётся агенту.
        assertThat((String) read(ticket(waiting, agent, "AGT"), "$.status.category")).isIn("new", "assigned");

        String result = body(perform(as(post("/api/v1/problems/" + id + "/resolve-incidents"), agent, "AGT")
                .content("{\"comment\":\"Проверено\"}")).andExpect(status().isOk()));
        assertThat((List<String>) read(result, "$.resolved")).isEmpty();
        assertThat((List<String>) read(result, "$.skipped[*].reason")).containsExactly("no_assignee");
    }

    @Test
    void changeFromProblemIsLinked() throws Exception {
        String json = createProblem(agent, "AGT", uniq("Утечка памяти в шлюзе"));
        String id = read(json, "$.id");
        String change = body(perform(as(post("/api/v1/problems/" + id + "/changes"), agent, "AGT")
                .content("{\"title\":\"Обновить шлюз до 2.4\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(Matchers.startsWith("CHG-"))));
        String view = body(perform(as(get("/api/v1/problems/" + id), agent, "AGT")));
        assertThat((List<String>) read(view, "$.changes[*].id")).containsExactly((String) read(change, "$.id"));
        perform(as(post("/api/v1/problems/" + id + "/changes"), requester, "REQ").content("{\"title\":\"X\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void recurringIncidentsAreGroupedAndMarkedWithProblems() throws Exception {
        UUID category = Ids.newId();
        String a = incident(uniq("Нет звука"));
        String b = incident(uniq("Нет звука"));
        String c = incident(uniq("Нет звука"));
        for (String id : List.of(a, b, c)) {
            TenantContext.runAs(UUID.fromString(DEMO), () -> jdbc.sql(
                    "update ticket set category_id = :c where id = cast(:id as uuid)")
                    .param("c", category).param("id", id).update());
        }
        perform(as(get("/api/v1/problems/recurring"), agent, "AGT")).andExpect(status().isForbidden());
        String list = body(perform(as(get("/api/v1/problems/recurring").param("dimension", "category")
                .param("minCount", "3").param("limit", "100"), prb, "PRB")).andExpect(status().isOk()));
        List<Integer> counts = read(list, "$[?(@.key == '" + category + "')].count");
        assertThat(counts).containsExactly(3);
        List<List<String>> ids = read(list, "$[?(@.key == '" + category + "')].incidentIds");
        assertThat(ids.getFirst()).containsExactlyInAnyOrder(a, b, c);

        String p = body(perform(as(post("/api/v1/problems"), prb, "PRB")
                .content("{\"title\":\"Повторы: нет звука\",\"source\":\"recurring\",\"incidentIds\":[\"" + a + "\",\""
                        + b + "\",\"" + c + "\"]}")).andExpect(status().isCreated()));
        String again = body(perform(as(get("/api/v1/problems/recurring").param("dimension", "category")
                .param("minCount", "3").param("limit", "100"), prb, "PRB")));
        List<List<String>> problems = read(again, "$[?(@.key == '" + category + "')].problems[*].number");
        assertThat(problems.toString()).contains((String) read(p, "$.number"));
        perform(as(get("/api/v1/problems/recurring").param("dimension", "x"), prb, "PRB"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void otherModulesCreateProblemsThroughApi() throws Exception {
        String inc = incident(uniq("Сбой оплаты"));
        ProblemRef ref = TenantContext.callAs(UUID.fromString(DEMO), () -> problemCommands.create(new NewProblem(
                "Разбор: сбой оплаты", "PIR", "major_incident", List.of(UUID.fromString(inc)), null, null,
                UUID.fromString(group), "Ошибка балансировщика",
                List.of(new NewProblem.TimelineItem(java.time.Instant.parse("2026-11-06T09:00:00Z"), "Объявлен")),
                UUID.fromString(lead))));
        assertThat(ref.number()).startsWith("PRB-");
        perform(as(get("/api/v1/problems/" + ref.id()), agent, "AGT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("major_incident"))
                .andExpect(jsonPath("$.rootCause").value("Ошибка балансировщика"))
                .andExpect(jsonPath("$.analysis.timeline.length()").value(2))
                .andExpect(jsonPath("$.incidents[0].id").value(inc));
    }
}
