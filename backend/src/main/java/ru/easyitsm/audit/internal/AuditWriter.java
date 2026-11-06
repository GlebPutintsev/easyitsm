package ru.easyitsm.audit.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;
import ru.easyitsm.audit.api.AuditEntry;
import ru.easyitsm.audit.api.AuditLog;
import ru.easyitsm.audit.api.Masking;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.identity.api.UserSummary;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.ActorPrincipal;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.TraceIdFilter;

/**
 * Пишет запись журнала (SEC-30): кто, что, когда, откуда, объект, результат, трассировка. Номер, время,
 * {@code prev_hash} и {@code hash} ставит триггер базы, приложение их не задаёт. Детали маскируются.
 */
@Service
class AuditWriter implements AuditLog {

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final UserDirectory directory;

    private final TransactionTemplate separate;

    AuditWriter(JdbcClient jdbc, ObjectMapper json, UserDirectory directory, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.directory = directory;
        this.separate = new TransactionTemplate(txManager);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Запись в собственной транзакции и на своём соединении с арендатором записи: для слушателей событий после
     * фиксации (ресурсы исходной транзакции ещё привязаны к потоку) и для отказов в доступе, когда транзакция
     * запроса уже откатилась.
     */
    void recordSeparately(AuditEntry entry) {
        separate.executeWithoutResult(status -> insert(entry));
    }

    @Override
    @Transactional
    public void record(AuditEntry entry) {
        insert(entry);
    }

    private void insert(AuditEntry entry) {
        UUID tenant = TenantContext.require();
        Optional<ActorPrincipal> current = CurrentActor.get().filter(a -> tenant.equals(a.tenantId()));
        UUID actorId = entry.actorId() != null ? entry.actorId() : current.map(ActorPrincipal::userId).orElse(null);
        String login = entry.actorLogin();
        if (login == null && actorId != null) {
            login = current.filter(a -> actorId.equals(a.userId())).map(ActorPrincipal::login)
                    .or(() -> directory.findUser(actorId).map(UserSummary::login))
                    .orElse(null);
        }
        Optional<HttpServletRequest> request = currentRequest();
        String ip = entry.ip() != null ? entry.ip() : request.map(HttpServletRequest::getRemoteAddr).orElse(null);
        String userAgent = request.map(r -> r.getHeader("User-Agent")).orElse(null);
        String trace = TraceIdFilter.current();
        Map<String, Object> details = Masking.details(entry.details());

        jdbc.sql("""
                insert into audit_log (id, tenant_id, category, action, result, actor_id, actor_login,
                                       object_type, object_id, ip, user_agent, trace_id, details)
                values (:id, :tenant, :category, :action, :result, :actor, :login,
                        :objectType, :objectId, :ip, :ua, :trace, cast(:details as jsonb))""")
                .param("id", Ids.newId())
                .param("tenant", tenant)
                .param("category", entry.category().code())
                .param("action", entry.action())
                .param("result", entry.result().code())
                .param("actor", actorId)
                .param("login", cut(Masking.text(login), 256))
                .param("objectType", cut(entry.objectType(), 200))
                .param("objectId", cut(Masking.text(entry.objectId()), 200))
                .param("ip", cut(ip, 64))
                .param("ua", cut(userAgent, 512))
                .param("trace", "none".equals(trace) ? null : cut(trace, 64))
                .param("details", json.writeValueAsString(details))
                .update();
    }

    static Optional<HttpServletRequest> currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return Optional.of(attrs.getRequest());
        }
        return Optional.empty();
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
