package ru.easyitsm.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.easyitsm.licensing.api.LicenseQueries;
import ru.easyitsm.licensing.internal.TestLicenses;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.web.ApiException;

/** «Управление → Лицензия»: права, загрузка, отказ для неверного и просроченного файла (ADM-07). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LicenseApiTest {

    static final String TENANT = "01930000-0000-7000-8000-000000000001";
    static final String USER = Ids.newId().toString();

    @Autowired
    MockMvc mvc;

    @Autowired
    LicenseQueries licenses;

    @Test
    void onlyAdminSeesLicense() throws Exception {
        mvc.perform(get("/api/v1/admin/license")).andExpect(status().isUnauthorized());
        mvc.perform(as(get("/api/v1/admin/license"), "AGT")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/admin/license"), "ADM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").exists())
                .andExpect(jsonPath("$.maxAgents").isNumber());
    }

    @Test
    void agentCannotInstallLicense() throws Exception {
        String file = TestLicenses.issue("EI-AGT", 500, Instant.now(), Instant.now().plus(365, ChronoUnit.DAYS));
        mvc.perform(as(put("/api/v1/admin/license"), "AGT").contentType(MediaType.APPLICATION_JSON).content(file))
                .andExpect(status().isForbidden());
    }

    @Test
    void installsValidLicenseAndAppliesLimit() throws Exception {
        Instant now = Instant.now();
        // issuedAt в будущем: этот файл новее любого, загруженного в других тестах.
        String file = TestLicenses.issue("EI-API-1", 7, now.plus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS),
                now.plus(400, ChronoUnit.DAYS));
        mvc.perform(as(put("/api/v1/admin/license"), "ADM").contentType(MediaType.APPLICATION_JSON).content(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("VALID"))
                .andExpect(jsonPath("$.licenseId").value("EI-API-1"))
                .andExpect(jsonPath("$.maxAgents").value(7));

        assertThat(licenses.current().maxAgents()).isEqualTo(7);
        licenses.requireAgentSeats(7);
        assertThatThrownBy(() -> licenses.requireAgentSeats(8))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo("license_agent_limit");
    }

    @Test
    void rejectsForgedLicense() throws Exception {
        String file = TestLicenses.issue("EI-FORGED", 5, Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS))
                .replace("\"signature\":\"", "\"signature\":\"AAAA");
        mvc.perform(as(put("/api/v1/admin/license"), "ADM").contentType(MediaType.APPLICATION_JSON).content(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("license_bad_signature"))
                .andExpect(jsonPath("$.trace_id").exists());
    }

    @Test
    void rejectsExpiredLicense() throws Exception {
        Instant now = Instant.now();
        String file = TestLicenses.issue("EI-OLD", 5, now.minus(400, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));
        mvc.perform(as(put("/api/v1/admin/license"), "ADM").contentType(MediaType.APPLICATION_JSON).content(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("license_expired"));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String roles) {
        return req.header("X-Dev-User", USER).header("X-Dev-Tenant", TENANT).header("X-Dev-Roles", roles);
    }
}
