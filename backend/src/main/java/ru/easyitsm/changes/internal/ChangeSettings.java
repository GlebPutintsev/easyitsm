package ru.easyitsm.changes.internal;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.changes.api.RiskLevel;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;

/**
 * Настройки изменений арендатора одним документом: маршрут согласования по уровню риска (CHG-04), сокращённый
 * маршрут и срок последующего согласования экстренных изменений (CHG-12). Без строки — значения по умолчанию.
 */
@Component
class ChangeSettings {

    /**
     * Маршрут для уровня риска.
     *
     * @param manager нужно утверждение менеджера изменений (роль CHG)
     * @param groupId группа согласования (identity), любой участник которой утверждает; null — нет
     * @param cab     нужно решение CAB
     */
    record Route(boolean manager, UUID groupId, boolean cab) {
    }

    /**
     * @param preApproval            экстренное изменение до работ утверждает менеджер изменений (сокращённый маршрут);
     *                               false — сразу «Запланировано»
     * @param postApprovalBusinessDays срок последующего согласования после работ, рабочие дни
     */
    record Emergency(boolean preApproval, int postApprovalBusinessDays) {
    }

    /**
     * @param requireAffected для отправки на оценку нужна хотя бы одна КЕ или услуга
     * @param timeZone        часовой пояс по умолчанию для окна работ
     */
    record Doc(Route low, Route medium, Route high, Emergency emergency, boolean requireAffected, String timeZone,
               Long version) {

        Route route(RiskLevel level) {
            return switch (level) {
                case LOW -> low;
                case MEDIUM -> medium;
                case HIGH -> high;
            };
        }

        Doc withVersion(Long v) {
            return new Doc(low, medium, high, emergency, requireAffected, timeZone, v);
        }
    }

    static final Doc DEFAULTS = new Doc(
            new Route(true, null, false),
            new Route(true, null, true),
            new Route(true, null, true),
            new Emergency(false, 2),
            true,
            "Europe/Moscow",
            null);

    private final JdbcClient jdbc;
    private final Json json;

    ChangeSettings(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    Doc get() {
        return jdbc.sql("select doc::text as doc, version from change_settings")
                .query((rs, n) -> json.read(rs.getString("doc"), Doc.class).withVersion(rs.getLong("version")))
                .optional().orElse(DEFAULTS.withVersion(0L));
    }

    @Transactional
    Doc save(Doc next, String ifMatch) {
        Doc current = get();
        ETags.requireMatch(ifMatch, current.version());
        validate(next);
        Doc stored = next.withVersion(null);
        long version = current.version() + 1;
        int n = jdbc.sql("""
                insert into change_settings (tenant_id, doc, updated_at, updated_by, version)
                values (:tenant, cast(:doc as jsonb), now(), :actor, :version)
                on conflict (tenant_id) do update set doc = excluded.doc, updated_at = excluded.updated_at,
                    updated_by = excluded.updated_by, version = excluded.version
                 where change_settings.version = :expected""")
                .param("tenant", TenantContext.require()).param("doc", json.write(stored))
                .param("actor", CurrentActor.userId().orElse(null)).param("version", version)
                .param("expected", current.version()).update();
        if (n == 0) {
            throw ApiException.conflict("version_conflict", "Настройки изменены другим пользователем. Обновите и повторите");
        }
        return stored.withVersion(version);
    }

    private static void validate(Doc d) {
        if (d.low() == null || d.medium() == null || d.high() == null) {
            throw ChangeProblem.field("low", "Укажите маршрут для каждого уровня риска");
        }
        if (d.emergency() == null || d.emergency().postApprovalBusinessDays() < 1
                || d.emergency().postApprovalBusinessDays() > 30) {
            throw ChangeProblem.field("emergency.postApprovalBusinessDays", "Срок последующего согласования — от 1 до 30 рабочих дней");
        }
        try {
            ZoneId.of(d.timeZone());
        } catch (RuntimeException e) {
            throw ChangeProblem.field("timeZone", "Неизвестный часовой пояс");
        }
    }

    /**
     * Срок через {@code days} рабочих дней (понедельник — пятница) в часовом поясе арендатора. Праздники учтёт
     * общий рабочий график, когда модуль SLA отдаст его через API.
     */
    static Instant addBusinessDays(Instant from, int days, String zone) {
        ZonedDateTime t = from.atZone(ZoneId.of(zone));
        int left = days;
        while (left > 0) {
            t = t.plusDays(1);
            if (t.getDayOfWeek() != DayOfWeek.SATURDAY && t.getDayOfWeek() != DayOfWeek.SUNDAY) {
                left--;
            }
        }
        return t.toInstant();
    }
}
