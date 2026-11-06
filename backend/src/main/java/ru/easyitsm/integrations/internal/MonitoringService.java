package ru.easyitsm.integrations.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.admin.api.ConfigHistory;
import ru.easyitsm.admin.api.DictionaryItem;
import ru.easyitsm.admin.api.ReasonCodes;
import ru.easyitsm.identity.api.DirectoryAdmin;
import ru.easyitsm.identity.api.GroupSummary;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.identity.api.UserSummary;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.PageParams;

/**
 * Источники мониторинга для «Управления → Интеграции». Каждое изменение пишется в журнал версий настроек
 * как {@code integrations.monitoring_source} (без токена).
 */
@Service
class MonitoringService {

    static final String HISTORY_ENTITY = "integrations.monitoring_source";
    private static final Set<String> KINDS = Set.of("zabbix", "alertmanager", "generic");
    private static final Set<String> LEVELS = Set.of("high", "medium", "low");

    /** Источник с именами пользователя и группы для экрана. */
    record SourceView(MonitoringSource source, String requesterName, String groupName) {
    }

    /** Источник и токен: токен показывается только в этом ответе. */
    record WithToken(SourceView source, String token) {
    }

    private final SourceStore sources;
    private final AlertStore alerts;
    private final EventLog events;
    private final UserDirectory users;
    private final DirectoryAdmin directory;
    private final ReasonCodes reasons;
    private final ConfigHistory history;

    MonitoringService(SourceStore sources, AlertStore alerts, EventLog events, UserDirectory users,
                      DirectoryAdmin directory, ReasonCodes reasons, ConfigHistory history) {
        this.sources = sources;
        this.alerts = alerts;
        this.events = events;
        this.users = users;
        this.directory = directory;
        this.reasons = reasons;
        this.history = history;
    }

    @Transactional(readOnly = true)
    List<SourceView> list() {
        return sources.list().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    SourceView get(UUID id) {
        return view(find(id));
    }

    @Transactional
    WithToken create(SourceInput input) {
        SourceInput in = normalize(input, true);
        if (in.requesterId() == null) {
            in = withRequester(in, serviceUser(in.name()));
        }
        validate(in);
        String token = Tokens.generate();
        MonitoringSource created = find(sources.insert(in, token));
        record(created, "create");
        return new WithToken(view(created), token);
    }

    @Transactional
    SourceView update(UUID id, String ifMatch, SourceInput input) {
        MonitoringSource current = find(id);
        ETags.requireMatch(ifMatch, current.version());
        SourceInput merged = normalize(input.over(current), false);
        validate(merged);
        if (!sources.update(id, current.version(), merged)) {
            throw ApiException.conflict("version_conflict", "Источник изменён другим пользователем. Обновите и повторите");
        }
        MonitoringSource updated = find(id);
        record(updated, "update");
        return view(updated);
    }

    /** Новый токен; старый перестаёт приниматься сразу. */
    @Transactional
    WithToken rotate(UUID id) {
        find(id);
        String token = Tokens.generate();
        sources.rotateToken(id, token);
        MonitoringSource updated = find(id);
        history.record(HISTORY_ENTITY, id.toString(), updated.name(), "update", updated.snapshot(), "Перевыпуск токена");
        return new WithToken(view(updated), token);
    }

    @Transactional
    void delete(UUID id) {
        MonitoringSource current = find(id);
        if (!sources.delete(id)) {
            throw ApiException.notFound("Источник");
        }
        record(current, "delete");
    }

    @Transactional(readOnly = true)
    List<AlertStore.Row> activeAlerts(UUID id) {
        find(id);
        return alerts.active(id, 200);
    }

    @Transactional(readOnly = true)
    CursorPage<EventLog.Entry> events(UUID sourceId, String outcome, PageParams page) {
        return events.page(sourceId, outcome, page);
    }

    private MonitoringSource find(UUID id) {
        return sources.find(id).orElseThrow(() -> ApiException.notFound("Источник"));
    }

    private SourceView view(MonitoringSource s) {
        String requester = users.findUser(s.requesterId()).map(UserSummary::displayName).orElse(null);
        String group = s.groupId() == null ? null
                : users.findGroup(s.groupId()).map(GroupSummary::name).orElse(null);
        return new SourceView(s, requester, group);
    }

    private void record(MonitoringSource s, String action) {
        history.record(HISTORY_ENTITY, s.id().toString(), s.name(), action, s.snapshot(), null);
    }

    /** Значения по умолчанию и полная карта важности. */
    private SourceInput normalize(SourceInput in, boolean creating) {
        Map<String, MonitoringSource.Impact> map = new LinkedHashMap<>(MonitoringSource.defaultSeverityMap());
        if (in.severityMap() != null) {
            in.severityMap().forEach((k, v) -> {
                if (!Severity.isCode(k)) {
                    throw invalid("Неизвестная важность «" + k + "»");
                }
                if (v == null || !LEVELS.contains(v.impact()) || !LEVELS.contains(v.urgency())) {
                    throw invalid("Влияние и срочность для «" + k + "»: high, medium или low");
                }
                map.put(k, v);
            });
        }
        return new SourceInput(
                in.name() == null ? null : in.name().strip(),
                in.kind(),
                creating ? !Boolean.FALSE.equals(in.enabled()) : in.enabled(),
                in.requesterId(),
                in.groupId(),
                false,
                in.minSeverity() == null ? "warning" : in.minSeverity(),
                map,
                creating ? !Boolean.FALSE.equals(in.autoResolve()) : in.autoResolve(),
                in.resolutionCode() == null || in.resolutionCode().isBlank() ? "fixed" : in.resolutionCode().strip());
    }

    private void validate(SourceInput in) {
        if (in.name() == null || in.name().isBlank()) {
            throw invalid("Укажите название источника");
        }
        if (in.name().length() > 200) {
            throw invalid("Название — не длиннее 200 символов");
        }
        if (in.kind() == null || !KINDS.contains(in.kind())) {
            throw invalid("Вид источника: zabbix, alertmanager или generic");
        }
        if (!Severity.isCode(in.minSeverity())) {
            throw invalid("Порог важности: critical, high, warning или info");
        }
        UserSummary requester = users.findUser(in.requesterId())
                .orElseThrow(() -> invalid("Пользователь, от имени которого создаются инциденты, не найден"));
        if (!requester.active()) {
            throw invalid("Пользователь, от имени которого создаются инциденты, заблокирован");
        }
        if (in.groupId() != null && users.findGroup(in.groupId()).isEmpty()) {
            throw invalid("Группа не найдена");
        }
        boolean known = reasons.active(ReasonCodes.Kind.RESOLUTION).stream().map(DictionaryItem::code)
                .anyMatch(in.resolutionCode()::equals);
        if (!known) {
            throw invalid("Код решения «" + in.resolutionCode() + "» не найден среди активных");
        }
    }

    /** Служебный пользователь источника: без почты и пароля, войти под ним нельзя. */
    private UUID serviceUser(String name) {
        if (name == null || name.isBlank()) {
            throw invalid("Укажите название источника");
        }
        String login = "monitoring-" + Ids.newId().toString().substring(24);
        return directory.createUser(new DirectoryAdmin.NewUser(login, null,
                AlertFormats.cut("Мониторинг: " + name.strip(), 200), "ru", null, null, null));
    }

    private static SourceInput withRequester(SourceInput in, UUID requester) {
        return new SourceInput(in.name(), in.kind(), in.enabled(), requester, in.groupId(), false, in.minSeverity(),
                in.severityMap(), in.autoResolve(), in.resolutionCode());
    }

    static ApiException invalid(String detail) {
        return ApiException.badRequest("invalid_source", detail);
    }
}
