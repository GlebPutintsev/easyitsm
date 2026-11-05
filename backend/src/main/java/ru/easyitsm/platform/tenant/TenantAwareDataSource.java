package ru.easyitsm.platform.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Передаёт арендатора в PostgreSQL при каждой выдаче соединения: {@code app.tenant_id}.
 * Политики RLS сравнивают с ним {@code tenant_id} строки через функцию {@code app_current_tenant()}.
 * Без арендатора значение пустое, и RLS не пропускает ни одной строки: запрет по умолчанию.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String SET_TENANT = "select set_config('app.tenant_id', ?, false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withTenant(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withTenant(super.getConnection(username, password));
    }

    private Connection withTenant(Connection connection) throws SQLException {
        String tenant = TenantContext.current().map(UUID::toString).orElse("");
        try (PreparedStatement ps = connection.prepareStatement(SET_TENANT)) {
            ps.setString(1, tenant);
            ps.execute();
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
