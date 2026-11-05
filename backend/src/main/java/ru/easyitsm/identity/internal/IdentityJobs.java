package ru.easyitsm.identity.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.identity.api.Role;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenancyProperties;

/** Фоновые задачи identity и создание первого администратора. */
@Component
class IdentityJobs implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IdentityJobs.class);

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final UserAdminService userAdmin;
    private final TransactionTemplate tx;
    private final IdentityProperties props;
    private final TenancyProperties tenancy;

    IdentityJobs(JdbcClient jdbc, UserRepository users, UserAdminService userAdmin, TransactionTemplate tx,
                 IdentityProperties props, TenancyProperties tenancy) {
        this.jdbc = jdbc;
        this.users = users;
        this.userAdmin = userAdmin;
        this.tx = tx;
        this.props = props;
        this.tenancy = tenancy;
    }

    /**
     * Первый администратор инсталляции: создаётся, если заданы
     * {@code EASYITSM_IDENTITY_BOOTSTRAP_ADMIN_LOGIN} и {@code ..._PASSWORD}, а пользователей в арендаторе ещё нет.
     * Получает ADM и SEC; MFA он подключит при первом входе.
     */
    @Override
    public void run(ApplicationArguments args) {
        var b = props.bootstrap();
        if (b.adminLogin() == null || b.adminLogin().isBlank() || b.adminPassword() == null
                || tenancy.defaultTenantId() == null) {
            return;
        }
        Tenancy.run(tenancy.defaultTenantId(), () -> tx.executeWithoutResult(s -> {
            if (users.countAll() > 0) {
                return;
            }
            var view = userAdmin.create(new UserCommands.Create(b.adminLogin(), b.adminEmail(), "Администратор",
                    "ru", null, b.adminPassword(), null, null, null));
            for (Role role : new Role[] {Role.ADM, Role.SEC}) {
                jdbc.sql("""
                        insert into role_grant (id, tenant_id, role, user_id, reason)
                        values (:id, :tenant, :role, :user, 'Первый администратор инсталляции')""")
                        .param("id", Ids.newId()).param("tenant", tenancy.defaultTenantId())
                        .param("role", role.name()).param("user", view.id()).update();
            }
            log.info("Создан первый администратор {}", b.adminLogin());
        }));
    }

    /** Плановые задачи выполняются только на узле с ролью worker: там включён {@code @EnableScheduling}. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    void purgeExpired() {
        Integer n = jdbc.sql("select identity_purge_expired()").query(Integer.class).single();
        if (n != null && n > 0) {
            log.info("Удалено устаревших записей входа: {}", n);
        }
    }
}
