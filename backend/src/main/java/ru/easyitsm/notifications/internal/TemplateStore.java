package ru.easyitsm.notifications.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;

/** Шаблоны арендатора поверх встроенных (NTF-02). */
@Service
class TemplateStore {

    static final List<String> LOCALES = List.of("ru", "en");

    private final JdbcClient jdbc;

    TemplateStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param custom  шаблон изменён арендатором
     * @param version версия записи арендатора для If-Match; 0 — встроенный
     */
    record TemplateView(String kind, String locale, String subject, String body, boolean custom, long version,
                        boolean email, boolean push, List<String> variables, String defaultSubject,
                        String defaultBody) {
    }

    record TemplateInput(@NotBlank @Size(max = 500) String subject, @Size(max = 20000) String body) {
    }

    /** Действующий шаблон: арендатора или встроенный. */
    @Transactional(readOnly = true)
    public Templates.Template effective(String kind, String locale) {
        return own(kind, locale).map(o -> new Templates.Template(o.subject(), o.body()))
                .orElseGet(() -> Templates.builtIn(kind, locale));
    }

    @Transactional(readOnly = true)
    public List<TemplateView> list() {
        List<TemplateView> out = new ArrayList<>();
        for (String kind : Templates.KINDS.keySet()) {
            for (String locale : LOCALES) {
                out.add(view(kind, locale));
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public TemplateView view(String kind, String locale) {
        Templates.Kind k = requireKind(kind, locale);
        Templates.Template builtIn = Templates.builtIn(kind, locale);
        Optional<Own> own = own(kind, locale);
        return new TemplateView(kind, locale, own.map(Own::subject).orElse(builtIn.subject()),
                own.map(Own::body).orElse(builtIn.body()), own.isPresent(), own.map(Own::version).orElse(0L),
                k.email(), k.push(), k.variables(), builtIn.subject(), builtIn.body());
    }

    @Transactional
    public TemplateView save(String kind, String locale, TemplateInput in, String ifMatch) {
        requireKind(kind, locale);
        Optional<Own> own = own(kind, locale);
        Timestamp now = Timestamp.from(Instant.now());
        UUID actor = CurrentActor.userId().orElse(null);
        String body = in.body() == null ? "" : in.body();
        if (own.isPresent()) {
            ETags.requireMatch(ifMatch, own.get().version());
            jdbc.sql("""
                    update notification_template set subject = :subject, body = :body, updated_at = :now,
                        updated_by = :actor, version = version + 1
                     where id = :id""")
                    .param("id", own.get().id()).param("subject", in.subject().strip()).param("body", body)
                    .param("now", now).param("actor", actor).update();
        } else {
            jdbc.sql("""
                    insert into notification_template (id, tenant_id, kind, locale, subject, body, created_at, created_by,
                        updated_at, updated_by, version)
                    values (:id, :tenant, :kind, :locale, :subject, :body, :now, :actor, :now, :actor, 1)""")
                    .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("kind", kind)
                    .param("locale", locale).param("subject", in.subject().strip()).param("body", body)
                    .param("now", now).param("actor", actor).update();
        }
        return view(kind, locale);
    }

    /** Вернуть встроенный шаблон. */
    @Transactional
    public TemplateView reset(String kind, String locale) {
        requireKind(kind, locale);
        jdbc.sql("delete from notification_template where kind = :kind and locale = :locale")
                .param("kind", kind).param("locale", locale).update();
        return view(kind, locale);
    }

    private record Own(UUID id, String subject, String body, long version) {
    }

    private Optional<Own> own(String kind, String locale) {
        return jdbc.sql("select id, subject, body, version from notification_template where kind = :k and locale = :l")
                .param("k", kind).param("l", locale)
                .query((rs, n) -> new Own(rs.getObject("id", UUID.class), rs.getString("subject"), rs.getString("body"),
                        rs.getLong("version")))
                .optional();
    }

    static Templates.Kind requireKind(String kind, String locale) {
        Templates.Kind k = Templates.KINDS.get(kind);
        if (k == null || !LOCALES.contains(locale)) {
            throw ApiException.notFound("Шаблон");
        }
        return k;
    }

    /** Пример данных для предпросмотра (NTF-02). */
    static Map<String, String> sample(String locale, boolean confidential) {
        boolean en = "en".equals(locale);
        Map<String, String> m = new java.util.HashMap<>(Map.ofEntries(
                Map.entry("product", "Easy ITSM"),
                Map.entry("recipient.name", en ? "Anna Smirnova" : "Анна Смирнова"),
                Map.entry("ticket.number", "INC-000123"),
                Map.entry("ticket.title", en ? "VPN does not connect from home" : "Не подключается VPN из дома"),
                Map.entry("ticket.link", "https://itsm.example.com/portal/tickets/INC-000123"),
                Map.entry("ticket.status", en ? "In progress" : "В работе"),
                Map.entry("ticket.priority", "P2"),
                Map.entry("actor.name", en ? "Ivan Petrov" : "Иван Петров"),
                Map.entry("comment.body", en ? "Please restart the VPN client and try again." :
                        "Перезапустите, пожалуйста, VPN-клиент и попробуйте снова."),
                Map.entry("reason", en ? "Settings were reset on the server." : "Сбросили настройки на сервере."),
                Map.entry("group.name", "Service Desk"),
                Map.entry("sla.percent", "75"),
                Map.entry("sla.metric", en ? "resolution" : "решение"),
                Map.entry("sla.due", "06.11.2026 18:00"),
                Map.entry("approval.step", en ? "Line manager" : "Руководитель"),
                Map.entry("approval.due", "07.11.2026 12:00"),
                Map.entry("approval.link", "https://itsm.example.com/portal/approvals"),
                Map.entry("approval.outcome", en ? "approved" : "согласован"),
                Map.entry("reset.link", "https://itsm.example.com/reset-password?token=…"),
                Map.entry("reset.expires", "06.11.2026 18:00"),
                Map.entry("title", en ? "Message title" : "Заголовок сообщения"),
                Map.entry("body", en ? "Message text." : "Текст сообщения."),
                Map.entry("link", "https://itsm.example.com/agent"),
                Map.entry("confidential", "")));
        if (confidential) {
            NotificationModel.maskConfidential(m, locale);
        }
        return m;
    }
}
