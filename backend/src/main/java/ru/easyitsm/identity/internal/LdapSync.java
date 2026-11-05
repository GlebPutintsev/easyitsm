package ru.easyitsm.identity.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.identity.events.GroupMembershipChanged;
import ru.easyitsm.platform.tenant.TenancyProperties;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Синхронизация пользователей и групп из LDAP (ACC-10). Отключённый или удалённый в каталоге пользователь
 * блокируется, его сессии завершаются; при включённой синхронизации раз в 10 минут доступ прекращается
 * не позднее 15 минут.
 */
@Service
class LdapSync {

    private static final Logger log = LoggerFactory.getLogger(LdapSync.class);

    private final LdapClient ldap;
    private final UserRepository users;
    private final GroupRepository groups;
    private final UserAdminService userAdmin;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final IdentityProperties.Ldap props;
    private final TenancyProperties tenancy;

    LdapSync(LdapClient ldap, UserRepository users, GroupRepository groups, UserAdminService userAdmin,
             JdbcClient jdbc, TransactionTemplate tx, ApplicationEventPublisher events, IdentityProperties props,
             TenancyProperties tenancy) {
        this.ldap = ldap;
        this.users = users;
        this.groups = groups;
        this.userAdmin = userAdmin;
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.props = props.ldap();
        this.tenancy = tenancy;
    }

    record Report(int users, int created, int blocked, int unblocked, int groups) {
    }

    UUID tenant() {
        UUID tenant = props.tenantId() != null ? props.tenantId() : tenancy.defaultTenantId();
        if (tenant == null) {
            throw new IllegalStateException("Для LDAP задайте easyitsm.identity.ldap.tenant-id");
        }
        return tenant;
    }

    Report sync() {
        if (!ldap.enabled()) {
            return new Report(0, 0, 0, 0, 0);
        }
        // Выгрузка из каталога до транзакции: она может идти долго.
        List<LdapClient.LdapUser> directoryUsers = ldap.allUsers();
        Set<String> disabled = ldap.disabledDns();
        List<LdapClient.LdapGroup> directoryGroups = ldap.allGroups();
        return Tenancy.in(tenant(), () -> tx.execute(s -> apply(directoryUsers, disabled, directoryGroups)));
    }

    private Report apply(List<LdapClient.LdapUser> directoryUsers, Set<String> disabled,
                         List<LdapClient.LdapGroup> directoryGroups) {
        int created = 0;
        int blocked = 0;
        int unblocked = 0;
        Map<String, UserAccount> existing = new HashMap<>();
        users.findAllBySource("ldap").forEach(u -> existing.put(u.externalId(), u));
        Map<String, UUID> userByDn = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (var du : directoryUsers) {
            if (du.login() == null || du.login().isBlank()) {
                continue;
            }
            seen.add(du.externalId());
            UserAccount user;
            if (!existing.containsKey(du.externalId())) {
                try {
                    user = userAdmin.provision("ldap", du.externalId(), du.login(), du.email(), du.displayName());
                    created++;
                } catch (ru.easyitsm.platform.web.ApiException e) {
                    log.warn("LDAP: пропущен пользователь {}: {}", du.login(), e.code());
                    continue;
                }
            } else {
                user = userAdmin.provision("ldap", du.externalId(), du.login(), du.email(), du.displayName());
            }
            userByDn.put(du.dn(), user.getId());
            boolean off = disabled.contains(du.dn());
            if (off && user.active()) {
                userAdmin.block(user, "source", null);
                blocked++;
            } else if (!off && !user.active() && "source".equals(user.blockedReason())) {
                userAdmin.unblock(user, null);
                unblocked++;
            }
        }
        for (var entry : existing.entrySet()) {
            if (!seen.contains(entry.getKey()) && entry.getValue().active()) {
                userAdmin.block(entry.getValue(), "source", null);
                blocked++;
            }
        }
        for (var dg : directoryGroups) {
            syncGroup(dg, userByDn);
        }
        Report report = new Report(directoryUsers.size(), created, blocked, unblocked, directoryGroups.size());
        log.info("Синхронизация LDAP: {}", report);
        return report;
    }

    private void syncGroup(LdapClient.LdapGroup dg, Map<String, UUID> userByDn) {
        String name = dg.name() == null || dg.name().isBlank() ? dg.dn() : dg.name();
        UserGroup group = groups.findByExternalId("ldap", dg.dn()).orElse(null);
        if (group == null) {
            UserGroup fresh = new UserGroup(name, "", "support", "ldap", dg.dn());
            group = Duplicates.guard(() -> groups.save(fresh), "name_taken", name);
        } else if (!group.name().equals(name)) {
            group.setName(name);
            group = groups.save(group);
        }
        Set<UUID> wanted = new HashSet<>();
        for (String dn : dg.memberDns()) {
            UUID userId = userByDn.get(dn);
            if (userId != null) {
                wanted.add(userId);
            }
        }
        Set<UUID> current = new HashSet<>(jdbc.sql("select user_id from group_member where group_id = :g")
                .param("g", group.getId()).query(UUID.class).list());
        UUID tenant = TenantContext.require();
        for (UUID userId : wanted) {
            if (!current.contains(userId)) {
                jdbc.sql("insert into group_member (tenant_id, group_id, user_id) values (:t, :g, :u)")
                        .param("t", tenant).param("g", group.getId()).param("u", userId).update();
                events.publishEvent(new GroupMembershipChanged(tenant, group.getId(), userId, true, null));
            }
        }
        for (UUID userId : current) {
            if (!wanted.contains(userId)) {
                jdbc.sql("delete from group_member where group_id = :g and user_id = :u")
                        .param("g", group.getId()).param("u", userId).update();
                events.publishEvent(new GroupMembershipChanged(tenant, group.getId(), userId, false, null));
            }
        }
    }

    /** Плановая синхронизация на узле с ролью worker. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = {"easyitsm.worker.enabled", "easyitsm.identity.ldap.enabled"}, havingValue = "true")
    static class Schedule implements SchedulingConfigurer {

        private final LdapSync sync;
        private final IdentityProperties props;

        Schedule(LdapSync sync, IdentityProperties props) {
            this.sync = sync;
            this.props = props;
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            registrar.addFixedDelayTask(() -> {
                try {
                    sync.sync();
                } catch (RuntimeException e) {
                    log.error("Синхронизация LDAP не выполнена", e);
                }
            }, props.ldap().syncInterval());
        }
    }
}
