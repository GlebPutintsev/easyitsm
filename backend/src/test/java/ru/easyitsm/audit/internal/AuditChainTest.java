package ru.easyitsm.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.easyitsm.audit.api.AuditCategory;
import ru.easyitsm.audit.api.AuditEntry;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Цепочка хешей и неизменяемость журнала (SEC-31, T-18). Подмену делает владелец базы с отключёнными
 * триггерами — так действовал бы администратор БД; приложение этого сделать не может вовсе.
 */
class AuditChainTest extends AuditTestSupport {

    @Autowired
    AuditChainVerifier verifier;

    @Test
    void chainLinksEntriesAndVerifies() throws Exception {
        UUID tenant = newTenant();
        write(tenant, 5);
        var rows = TenantContext.callAs(tenant, () -> jdbc.sql(
                "select seq, prev_hash, hash from audit_log order by seq").query((rs, n) -> new String[] {
                rs.getString(1), rs.getString(2), rs.getString(3)}).list());
        assertThat(rows).hasSize(5);
        assertThat(rows.getFirst()[0]).isEqualTo("1");
        assertThat(rows.getFirst()[1]).isEqualTo(AuditChainVerifier.GENESIS);
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i)[1]).isEqualTo(rows.get(i - 1)[2]);
        }
        var result = verify(tenant);
        assertThat(result.valid()).isTrue();
        assertThat(result.checked()).isEqualTo(5);
        assertThat(result.headSeq()).isEqualTo(5);
    }

    @Test
    void applicationCannotChangeOrDeleteEntries() throws Exception {
        UUID tenant = newTenant();
        write(tenant, 1);
        TenantContext.runAs(tenant, () -> {
            assertThatThrownBy(() -> jdbc.sql("update audit_log set result = 'success'").update())
                    .rootCause().hasMessageContaining("permission denied");
            assertThatThrownBy(() -> jdbc.sql("delete from audit_log").update())
                    .rootCause().hasMessageContaining("permission denied");
            assertThatThrownBy(() -> jdbc.sql("truncate audit_log").update())
                    .rootCause().hasMessageContaining("permission denied");
            assertThatThrownBy(() -> jdbc.sql("update audit_chain_head set seq = 0").update())
                    .rootCause().hasMessageContaining("permission denied");
        });
        // Подставить свои номер и хеш при вставке нельзя: их ставит база
        TenantContext.runAs(tenant, () -> jdbc.sql("""
                insert into audit_log (id, tenant_id, category, action, result, prev_hash, hash, seq, at)
                values (gen_random_uuid(), :t, 'auth', 'auth.forged', 'success', 'x', 'y', 100, '2000-01-01')""")
                .param("t", tenant).update());
        assertThat(verify(tenant).valid()).isTrue();
        // Владельцу триггер тоже не даёт менять записи
        assertThatThrownBy(() -> owner("update audit_log set result = 'denied' where tenant_id = '" + tenant + "'"))
                .hasMessageContaining("нельзя изменять");
    }

    @Test
    void cannotWriteIntoAnotherTenantsLog() throws Exception {
        UUID tenant = newTenant();
        UUID victim = newTenant();
        write(victim, 1);
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> jdbc.sql("""
                insert into audit_log (id, tenant_id, category, action, result)
                values (gen_random_uuid(), :v, 'auth', 'auth.forged', 'success')""").param("v", victim).update()))
                .rootCause().hasMessageContaining("row-level security");
        assertThat(verify(victim).headSeq()).isEqualTo(1);
    }

    @Test
    void modifiedEntryIsDetected() throws Exception {
        UUID tenant = newTenant();
        write(tenant, 4);
        tamper("update audit_log set result = 'denied' where tenant_id = '" + tenant + "' and seq = 3");
        var result = verify(tenant);
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSeq()).isEqualTo(3);
        assertThat(result.problem()).isEqualTo("hash_mismatch");
    }

    @Test
    void deletedEntryIsDetected() throws Exception {
        UUID tenant = newTenant();
        write(tenant, 4);
        tamper("delete from audit_log where tenant_id = '" + tenant + "' and seq = 2");
        var result = verify(tenant);
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSeq()).isEqualTo(2);
        assertThat(result.problem()).isEqualTo("gap");
    }

    @Test
    void truncatedTailIsDetected() throws Exception {
        UUID tenant = newTenant();
        write(tenant, 4);
        tamper("delete from audit_log where tenant_id = '" + tenant + "' and seq = 4");
        var result = verify(tenant);
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSeq()).isEqualTo(4);
        assertThat(result.problem()).isEqualTo("truncated");
    }

    @Test
    void verifyEndpointRecordsItsResult() throws Exception {
        UUID tenant = newTenant();
        write(tenant, 2);
        mvc.perform(as(post("/api/v1/audit/verify"), tenant.toString(), sec, "AUD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.checked").value(2));
        assertThat(rows(tenant.toString(), "audit.verify")).hasSize(1);
        assertThat(verify(tenant).checked()).isEqualTo(3);
        mvc.perform(as(post("/api/v1/audit/verify"), tenant.toString(), sec, "ADM"))
                .andExpect(status().isForbidden());
    }

    private void write(UUID tenant, int n) {
        TenantContext.runAs(tenant, () -> {
            for (int i = 0; i < n; i++) {
                writer.record(AuditEntry.of(AuditCategory.ACCESS, "access.test").object("n", i).detail("i", i));
            }
        });
    }

    private AuditViews.Verification verify(UUID tenant) throws Exception {
        return TenantContext.callAs(tenant, verifier::verify);
    }

    private void tamper(String sql) throws Exception {
        owner("alter table audit_log disable trigger audit_log_no_update", sql,
                "alter table audit_log enable trigger audit_log_no_update");
    }
}
