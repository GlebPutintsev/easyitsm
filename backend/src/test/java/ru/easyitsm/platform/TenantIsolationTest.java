package ru.easyitsm.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Изоляция арендаторов на уровне БД (T-03, ADR-07). Проверяется не код сервисов, а сама RLS:
 * даже запрос без фильтра по tenant_id не видит и не пишет чужие строки.
 */
@SpringBootTest
@ActiveProfiles("test")
class TenantIsolationTest {

    static final UUID DEMO = UUID.fromString("01930000-0000-7000-8000-000000000001");
    static final UUID OTHER = UUID.fromString("01930000-0000-7000-8000-000000000002");

    @Autowired
    JdbcClient jdbc;

    @Test
    void appRoleCannotBypassRls() {
        Boolean bypass = jdbc.sql("select rolbypassrls or rolsuper from pg_roles where rolname = current_user")
                .query(Boolean.class).single();
        assertThat(bypass).isFalse();
    }

    @Test
    void rowsOfAnotherTenantAreInvisible() {
        UUID id = Ids.newId();
        TenantContext.runAs(DEMO, () -> insertAnnouncement(id, DEMO));

        TenantContext.runAs(OTHER, () -> assertThat(count(id)).isZero());
        TenantContext.runAs(DEMO, () -> assertThat(count(id)).isEqualTo(1));
    }

    @Test
    void withoutTenantNothingIsVisible() {
        UUID id = Ids.newId();
        TenantContext.runAs(DEMO, () -> insertAnnouncement(id, DEMO));
        assertThat(count(id)).isZero();
    }

    @Test
    void cannotWriteRowForAnotherTenant() {
        assertThatThrownBy(() -> TenantContext.runAs(DEMO, () -> insertAnnouncement(Ids.newId(), OTHER)))
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void cannotUpdateRowOfAnotherTenant() {
        UUID id = Ids.newId();
        TenantContext.runAs(DEMO, () -> insertAnnouncement(id, DEMO));
        TenantContext.runAs(OTHER, () -> {
            int updated = jdbc.sql("update announcement set title = 'взлом' where id = :id").param("id", id).update();
            assertThat(updated).isZero();
        });
    }

    private long count(UUID id) {
        return jdbc.sql("select count(*) from announcement where id = :id").param("id", id).query(Long.class).single();
    }

    private void insertAnnouncement(UUID id, UUID tenant) {
        jdbc.sql("""
                insert into announcement (id, tenant_id, kind, title, starts_at, created_at, updated_at, version)
                values (:id, :tenant, 'info', 'Тест', now(), now(), now(), 0)""")
                .param("id", id).param("tenant", tenant)
                .update();
    }
}
