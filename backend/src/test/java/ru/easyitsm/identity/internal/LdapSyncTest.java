package ru.easyitsm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.easyitsm.platform.id.Ids;

/**
 * LDAP на встроенном сервере UnboundID: вход паролем каталога с созданием пользователя при первом входе,
 * синхронизация групп и блокировка отключённых и удалённых в каталоге пользователей (ACC-10).
 */
class LdapSyncTest extends IdentityTestSupport {

    static final String BASE = "dc=example,dc=org";
    static final String RUN = Ids.newId().toString().substring(28);
    static final String ALICE = "alice" + RUN;
    static final String BOB = "bob" + RUN;
    static final String LDAP_PASSWORD = "Ldap-Password-2026"; // gitleaks:allow
    static final InMemoryDirectoryServer SERVER = start();

    @Autowired
    LdapSync sync;

    static InMemoryDirectoryServer start() {
        try {
            var config = new InMemoryDirectoryServerConfig(BASE);
            config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", 0));
            config.addAdditionalBindCredentials("cn=admin," + BASE, "admin-secret");
            var server = new InMemoryDirectoryServer(config);
            server.add("dn: " + BASE, "objectClass: top", "objectClass: domain", "dc: example");
            server.add("dn: ou=people," + BASE, "objectClass: organizationalUnit", "ou: people");
            server.add("dn: ou=groups," + BASE, "objectClass: organizationalUnit", "ou: groups");
            person(server, ALICE, "Алиса Иванова");
            person(server, BOB, "Борис Петров");
            server.add("dn: cn=helpdesk" + RUN + ",ou=groups," + BASE, "objectClass: groupOfNames",
                    "cn: helpdesk" + RUN, "member: uid=" + ALICE + ",ou=people," + BASE,
                    "member: uid=" + BOB + ",ou=people," + BASE);
            server.startListening();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void person(InMemoryDirectoryServer server, String uid, String name) throws Exception {
        server.add("dn: uid=" + uid + ",ou=people," + BASE, "objectClass: inetOrgPerson", "uid: " + uid,
                "cn: " + name, "sn: " + name.split(" ")[1], "mail: " + uid + "@example.org",
                "userPassword: " + LDAP_PASSWORD);
    }

    @DynamicPropertySource
    static void ldap(DynamicPropertyRegistry registry) {
        registry.add("easyitsm.identity.ldap.enabled", () -> "true");
        registry.add("easyitsm.identity.ldap.url", () -> "ldap://localhost:" + SERVER.getListenPort());
        registry.add("easyitsm.identity.ldap.base", () -> BASE);
        registry.add("easyitsm.identity.ldap.bind-dn", () -> "cn=admin," + BASE);
        registry.add("easyitsm.identity.ldap.bind-password", () -> "admin-secret");
        registry.add("easyitsm.identity.ldap.disabled-filter", () -> "(description=disabled)");
        registry.add("easyitsm.identity.ldap.tenant-id", () -> DEMO.toString());
        // Без планировщика: синхронизацию запускает сам тест.
        registry.add("easyitsm.worker.enabled", () -> "false");
    }

    @AfterAll
    static void stop() {
        SERVER.shutDown(true);
    }

    @Test
    void ldapUserLogsInWithDirectoryPasswordAndIsSyncedAndBlocked() throws Exception {
        // Первый вход паролем каталога создаёт пользователя.
        Browser alice = new Browser(mvc).open();
        alice.post("/api/v1/auth/login", loginJson(ALICE, LDAP_PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        alice.get("/api/v1/me").andExpect(jsonPath("$.source").value("ldap"))
                .andExpect(jsonPath("$.displayName").value("Алиса Иванова"));
        new Browser(mvc).open().post("/api/v1/auth/login", loginJson(ALICE, "")).andExpect(status().isUnauthorized());
        new Browser(mvc).open().post("/api/v1/auth/login", loginJson(ALICE, "wrong-password"))
                .andExpect(status().isUnauthorized());

        // Синхронизация добавляет Бориса и группу с обоими участниками.
        var report = sync.sync();
        assertThat(report.created()).isEqualTo(1);
        UUID bob = userId(BOB);
        UUID group = Tenancy.in(DEMO, () -> jdbc.sql("select id from user_group where name = :n")
                .param("n", "helpdesk" + RUN).query(UUID.class).single());
        assertThat(Tenancy.in(DEMO, () -> jdbc.sql("select count(*) from group_member where group_id = :g")
                .param("g", group).query(Integer.class).single())).isEqualTo(2);

        // Отключение в каталоге: блокировка и завершение сессий.
        SERVER.modify("uid=" + ALICE + ",ou=people," + BASE,
                new Modification(ModificationType.ADD, "description", "disabled"));
        assertThat(sync.sync().blocked()).isEqualTo(1);
        alice.get("/api/v1/me").andExpect(status().isUnauthorized());
        Browser admin = loggedInWithTotp(newUser("ADM"));
        admin.post("/api/v1/users/" + userId(ALICE) + "/unblock", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("blocked_in_source"));

        // Включение в каталоге снимает блокировку источника.
        SERVER.modify("uid=" + ALICE + ",ou=people," + BASE,
                new Modification(ModificationType.DELETE, "description", "disabled"));
        assertThat(sync.sync().unblocked()).isEqualTo(1);

        // Удаление из каталога и из группы.
        SERVER.delete("uid=" + BOB + ",ou=people," + BASE);
        SERVER.modify("cn=helpdesk" + RUN + ",ou=groups," + BASE,
                new Modification(ModificationType.DELETE, "member", "uid=" + BOB + ",ou=people," + BASE));
        sync.sync();
        assertThat(Tenancy.in(DEMO, () -> jdbc.sql("select state from user_account where id = :id")
                .param("id", bob).query(String.class).single())).isEqualTo("blocked");
        assertThat(Tenancy.in(DEMO, () -> jdbc.sql("select count(*) from group_member where group_id = :g")
                .param("g", group).query(Integer.class).single())).isEqualTo(1);
    }
}
