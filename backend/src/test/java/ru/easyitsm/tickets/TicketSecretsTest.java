package ru.easyitsm.tickets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/** TCK-17 через API: предупреждение, маскирование, отправка как есть только для поддержки. */
class TicketSecretsTest extends TicketApiSupport {

    static final String LEAK = "Не могу войти, мой пароль: Zx9-fake-Pass";

    @Test
    void warnsAndMasks() throws Exception {
        perform(asReq(post("/api/v1/tickets"))
                .content("{\"type\":\"incident\",\"title\":\"Вход\",\"description\":\"" + LEAK + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("secrets_detected"))
                .andExpect(jsonPath("$.findings[0].kind").value("password"));
        perform(asReq(post("/api/v1/tickets"))
                .content("{\"type\":\"incident\",\"title\":\"Вход\",\"description\":\"" + LEAK + "\",\"secrets\":\"mask\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value("Не могу войти, мой пароль: ********"));
        // Заявитель не может сохранить секрет как есть: allow превращается в mask
        perform(asReq(post("/api/v1/tickets"))
                .content("{\"type\":\"incident\",\"title\":\"Вход\",\"description\":\"" + LEAK + "\",\"secrets\":\"allow\"}"))
                .andExpect(jsonPath("$.description").value("Не могу войти, мой пароль: ********"));
    }

    @Test
    void commentsAreCheckedToo() throws Exception {
        String id = read(createIncident("Секрет в ответе"), "$.id");
        perform(asAgent(post("/api/v1/tickets/" + id + "/activity")).content("{\"body\":\"" + LEAK + "\"}"))
                .andExpect(status().isUnprocessableContent());
        perform(asAgent(post("/api/v1/tickets/" + id + "/activity"))
                .content("{\"body\":\"" + LEAK + "\",\"secrets\":\"mask\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("Не могу войти, мой пароль: ********"));
        perform(asAgent(post("/api/v1/tickets/secret-scan")).content("{\"text\":\"" + LEAK + "\"}"))
                .andExpect(jsonPath("$.findings.length()").value(1))
                .andExpect(jsonPath("$.masked").value("Не могу войти, мой пароль: ********"));
    }
}
