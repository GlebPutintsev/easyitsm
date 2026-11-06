package ru.easyitsm.admin.internal;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;
import tools.jackson.core.type.TypeReference;

/**
 * Состояние мастера первичной настройки (ADM-01): организация и отметки шагов. Нет строки — мастер не начат,
 * версия 0.
 */
@Service
class SetupService {

    /** Шаги мастера в порядке показа (10.16). */
    static final List<String> STEPS = List.of("organization", "login", "mail", "groups", "services", "sla", "demo");
    static final List<String> STEP_STATES = List.of("pending", "done", "skipped");
    private static final TypeReference<Map<String, String>> STEP_MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final AdminJson json;
    private final ConfigVersionService versions;

    SetupService(JdbcClient jdbc, AdminJson json, ConfigVersionService versions) {
        this.jdbc = jdbc;
        this.json = json;
        this.versions = versions;
    }

    record Organization(String name, String timezone, String locale) {
    }

    record State(Organization organization, Map<String, String> steps, String nextStep, Instant completedAt,
                 Instant demoLoadedAt, long version) {
    }

    @Transactional(readOnly = true)
    State get() {
        return jdbc.sql("""
                select org_name, timezone, locale, steps::text as steps, completed_at, demo_loaded_at, version
                  from setup_state""")
                .query((rs, n) -> state(new Organization(rs.getString("org_name"), rs.getString("timezone"),
                                rs.getString("locale")), json.read(rs.getString("steps"), STEP_MAP),
                        AdminJson.instant(rs, "completed_at"), AdminJson.instant(rs, "demo_loaded_at"),
                        rs.getLong("version")))
                .optional()
                .orElseGet(() -> state(new Organization("", "Europe/Moscow", "ru"), Map.of(), null, null, 0));
    }

    @Transactional
    State saveOrganization(String ifMatch, Organization org) {
        State current = get();
        ETags.requireMatch(ifMatch, current.version());
        String name = org.name() == null ? "" : org.name().trim();
        if (name.isEmpty() || name.length() > 255) {
            throw ApiException.badRequest("invalid_name", "Название организации: от 1 до 255 символов");
        }
        String tz = org.timezone() == null ? "Europe/Moscow" : org.timezone().trim();
        try {
            ZoneId.of(tz);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("invalid_timezone", "Неизвестный часовой пояс: " + tz);
        }
        String locale = org.locale() == null ? "ru" : org.locale();
        if (!List.of("ru", "en").contains(locale)) {
            throw ApiException.badRequest("invalid_locale", "Язык: ru или en");
        }
        Map<String, String> steps = new LinkedHashMap<>(current.steps());
        steps.put("organization", "done");
        State saved = write(current.version(), name, tz, locale, steps, current.completedAt(),
                current.demoLoadedAt());
        versions.record("admin.setup", "", "Организация", "update", saved.organization(), null);
        return saved;
    }

    @Transactional
    State setStep(String step, String status) {
        if (!STEPS.contains(step)) {
            throw ApiException.notFound("Шаг мастера");
        }
        if (!STEP_STATES.contains(status)) {
            throw ApiException.badRequest("invalid_status", "Состояние шага: pending, done или skipped");
        }
        State current = get();
        Map<String, String> steps = new LinkedHashMap<>(current.steps());
        steps.put(step, status);
        Organization o = current.organization();
        return write(current.version(), o.name(), o.timezone(), o.locale(), steps, current.completedAt(),
                current.demoLoadedAt());
    }

    @Transactional
    State complete() {
        State current = get();
        if (current.organization().name().isBlank()) {
            throw ApiException.conflict("organization_required", "Сначала укажите название организации");
        }
        Organization o = current.organization();
        Instant at = current.completedAt() == null ? Instant.now() : current.completedAt();
        State saved = write(current.version(), o.name(), o.timezone(), o.locale(), current.steps(), at,
                current.demoLoadedAt());
        versions.record("admin.setup", "", "Мастер настройки", "setup", Map.of("completedAt", at.toString(),
                "steps", saved.steps()), "Мастер настройки завершён");
        return saved;
    }

    /** Отметка о загрузке демоданных: второй раз загрузить нельзя (409). */
    @Transactional
    void markDemoLoaded() {
        State current = get();
        if (current.demoLoadedAt() != null) {
            throw ApiException.conflict("demo_already_loaded", "Демоданные уже загружены");
        }
        Organization o = current.organization();
        Map<String, String> steps = new LinkedHashMap<>(current.steps());
        steps.put("demo", "done");
        write(current.version(), o.name(), o.timezone(), o.locale(), steps, current.completedAt(), Instant.now());
    }

    private State write(long version, String name, String tz, String locale, Map<String, String> steps,
                        Instant completedAt, Instant demoLoadedAt) {
        UUID actor = CurrentActor.userId().orElse(null);
        var now = AdminJson.ts(Instant.now());
        String doc = json.write(steps);
        int changed;
        if (version == 0) {
            changed = jdbc.sql("""
                    insert into setup_state (id, tenant_id, org_name, timezone, locale, steps, completed_at,
                                             demo_loaded_at, created_at, created_by, updated_at, updated_by, version)
                    values (:id, :tenant, :name, :tz, :locale, cast(:steps as jsonb), :completed, :demo,
                            :now, :actor, :now, :actor, 1)
                    on conflict (tenant_id) do nothing""")
                    .param("id", Ids.newId()).param("tenant", TenantContext.require())
                    .param("name", name).param("tz", tz).param("locale", locale).param("steps", doc)
                    .param("completed", AdminJson.ts(completedAt)).param("demo", AdminJson.ts(demoLoadedAt))
                    .param("now", now).param("actor", actor).update();
        } else {
            changed = jdbc.sql("""
                    update setup_state set org_name = :name, timezone = :tz, locale = :locale,
                           steps = cast(:steps as jsonb), completed_at = :completed, demo_loaded_at = :demo,
                           updated_at = :now, updated_by = :actor, version = version + 1
                     where version = :version""")
                    .param("name", name).param("tz", tz).param("locale", locale).param("steps", doc)
                    .param("completed", AdminJson.ts(completedAt)).param("demo", AdminJson.ts(demoLoadedAt))
                    .param("now", now).param("actor", actor).param("version", version).update();
        }
        if (changed == 0) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "version_conflict",
                    "Настройку изменил другой администратор. Обновите и повторите");
        }
        return state(new Organization(name, tz, locale), steps, completedAt, demoLoadedAt, version + 1);
    }

    private static State state(Organization org, Map<String, String> steps, Instant completedAt, Instant demo,
                               long version) {
        Map<String, String> all = new LinkedHashMap<>();
        for (String s : STEPS) {
            all.put(s, steps.getOrDefault(s, "pending"));
        }
        String next = STEPS.stream().filter(s -> "pending".equals(all.get(s))).findFirst().orElse(null);
        return new State(org, all, next, completedAt, demo, version);
    }
}
