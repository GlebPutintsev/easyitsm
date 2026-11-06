package ru.easyitsm.attachments.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Антивирус ClamAV по INSTREAM: тестовый файл EICAR попадает в карантин, обычный файл проходит.
 * Запускается, если заданы {@code EASYITSM_TEST_S3_ENDPOINT} и {@code EASYITSM_TEST_CLAMD_HOST}.
 */
@SpringBootTest(properties = {
        "easyitsm.attachments.uploads-per-minute=1000",
        "easyitsm.attachments.antivirus.mode=clamd",
        "easyitsm.attachments.antivirus.host=${EASYITSM_TEST_CLAMD_HOST:localhost}"})
@EnabledIfEnvironmentVariable(named = "EASYITSM_TEST_CLAMD_HOST", matches = ".+")
class ClamavScanTest extends AttachmentApiTest {

    /** EICAR собирается из частей, чтобы антивирус машины разработчика не трогал исходники. */
    static byte[] eicar() {
        String s = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$" + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void eicarIsQuarantined() throws Exception {
        String id = upload(requester, "договор.txt", eicar(), "text/plain", "public");
        scanAll();
        mvc.perform(requester.sign(get("/api/v1/attachments/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("infected"))
                .andExpect(jsonPath("$.verdict").value(org.hamcrest.Matchers.containsStringIgnoringCase("eicar")));
        mvc.perform(requester.sign(get("/api/v1/attachments/" + id + "/download")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("attachment_quarantined"));
    }

    @Test
    void cleanFilePassesAntivirus() throws Exception {
        String id = upload(requester, "заметка.txt", "просто текст".getBytes(StandardCharsets.UTF_8), "text/plain",
                "public");
        scanAll();
        mvc.perform(requester.sign(get("/api/v1/attachments/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("clean"));
    }
}
