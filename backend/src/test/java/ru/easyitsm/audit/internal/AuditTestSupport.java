package ru.easyitsm.audit.internal;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Общая подготовка тестов журнала. Тесты, которые портят цепочку, работают в своём новом арендаторе:
 * его создаёт владелец базы, как это делает установка.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class AuditTestSupport {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    AuditWriter writer;

    @Value("${spring.datasource.url}")
    String dbUrl;
    @Value("${spring.flyway.user}")
    String ownerUser;
    @Value("${spring.flyway.password}")
    String ownerPassword;

    final String sec = Ids.newId().toString();

    static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String tenant, String user,
                                            String roles) {
        return req.header("X-Dev-User", user).header("X-Dev-Tenant", tenant).header("X-Dev-Roles", roles);
    }

    /** Новый арендатор, созданный владельцем базы. */
    UUID newTenant() throws SQLException {
        UUID id = Ids.newId();
        owner("insert into tenant (id, code, name) values ('" + id + "', 't" + id.toString().substring(24)
                + "', 'Аудит тест')");
        return id;
    }

    /** SQL от имени владельца базы — так действует администратор БД, который пытается подменить журнал. */
    void owner(String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(dbUrl, ownerUser, ownerPassword)) {
            c.setAutoCommit(false); // отключение триггера и правка — одной транзакцией, другим тестам не видно
            try (var st = c.createStatement()) {
                for (String sql : statements) {
                    st.execute(sql);
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Действия журнала арендатора с заданным кодом, от новых к старым. */
    List<Row> rows(String tenant, String action) {
        return in(tenant, () -> jdbc.sql("""
                        select seq, action, result, actor_id, object_type, object_id, details::text as details
                          from audit_log where action = :a order by seq desc limit 50""")
                .param("a", action)
                .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getString(7)))
                .list());
    }

    @SuppressWarnings("unchecked")
    static <T> T in(String tenant, java.util.function.Supplier<T> action) {
        Object[] out = new Object[1];
        TenantContext.runAs(UUID.fromString(tenant), () -> out[0] = action.get());
        return (T) out[0];
    }

    record Row(long seq, String action, String result, UUID actorId, String objectType, String objectId,
               String details) {
    }
}
