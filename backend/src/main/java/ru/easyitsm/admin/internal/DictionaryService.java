package ru.easyitsm.admin.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.admin.api.DictionaryItem;
import ru.easyitsm.admin.api.ReasonCodes;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;
import tools.jackson.core.type.TypeReference;

/**
 * Справочники кодов решения, приостановки и отмены (ADM-03). Нет строки — действуют значения по умолчанию
 * с версией 0. Код записан в заявках, поэтому его нельзя удалить или переименовать, только выключить.
 */
@Service
class DictionaryService implements ReasonCodes {

    static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");
    static final int MAX_ITEMS = 50;
    private static final TypeReference<List<DictionaryItem>> ITEMS = new TypeReference<>() {
    };

    /** Значения по умолчанию: те же коды, что были в рабочем месте агента до «Управления». */
    static final Map<Kind, List<DictionaryItem>> DEFAULTS = Map.of(
            Kind.RESOLUTION, List.of(
                    new DictionaryItem("fixed", "Устранено", true),
                    new DictionaryItem("workaround", "Обходное решение", true),
                    new DictionaryItem("consultation", "Консультация", true),
                    new DictionaryItem("no_repro", "Не воспроизводится", true),
                    new DictionaryItem("duplicate", "Дубликат", true)),
            Kind.PENDING, List.of(
                    new DictionaryItem("requester", "Ожидание ответа заявителя", true),
                    new DictionaryItem("vendor", "Ожидание поставщика", true),
                    new DictionaryItem("change", "Ожидание изменения", true),
                    new DictionaryItem("delivery", "Ожидание поставки оборудования", true)),
            Kind.CANCEL, List.of(
                    new DictionaryItem("duplicate", "Дубликат", true),
                    new DictionaryItem("requester", "Отозвана заявителем", true),
                    new DictionaryItem("mistake", "Создана по ошибке", true)));

    private final JdbcClient jdbc;
    private final AdminJson json;
    private final ConfigVersionService versions;

    DictionaryService(JdbcClient jdbc, AdminJson json, ConfigVersionService versions) {
        this.jdbc = jdbc;
        this.json = json;
        this.versions = versions;
    }

    record Dictionary(String kind, List<DictionaryItem> items, long version) {
    }

    static Kind kind(String code) {
        for (Kind k : Kind.values()) {
            if (k.code().equals(code)) {
                return k;
            }
        }
        throw ApiException.notFound("Справочник");
    }

    @Override
    @Transactional(readOnly = true)
    public List<DictionaryItem> active(Kind kind) {
        return get(kind).items().stream().filter(DictionaryItem::active).toList();
    }

    @Transactional(readOnly = true)
    List<Dictionary> all() {
        return java.util.Arrays.stream(Kind.values()).map(this::get).toList();
    }

    @Transactional(readOnly = true)
    Dictionary get(Kind kind) {
        return jdbc.sql("select items::text as items, version from admin_dictionary where kind = :kind")
                .param("kind", kind.code())
                .query((rs, n) -> new Dictionary(kind.code(), json.read(rs.getString("items"), ITEMS),
                        rs.getLong("version")))
                .optional()
                .orElseGet(() -> new Dictionary(kind.code(), DEFAULTS.get(kind), 0));
    }

    @Transactional
    Dictionary save(Kind kind, String ifMatch, List<DictionaryItem> items, String comment) {
        Dictionary current = get(kind);
        ETags.requireMatch(ifMatch, current.version());
        List<DictionaryItem> clean = validate(current.items(), items);
        String doc = json.write(clean);
        UUID actor = CurrentActor.userId().orElse(null);
        var now = AdminJson.ts(java.time.Instant.now());
        int changed;
        if (current.version() == 0) {
            changed = jdbc.sql("""
                    insert into admin_dictionary (id, tenant_id, kind, items, created_at, created_by, updated_at,
                                                  updated_by, version)
                    values (:id, :tenant, :kind, cast(:items as jsonb), :now, :actor, :now, :actor, 1)
                    on conflict (tenant_id, kind) do nothing""")
                    .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("kind", kind.code())
                    .param("items", doc).param("now", now).param("actor", actor).update();
        } else {
            changed = jdbc.sql("""
                    update admin_dictionary set items = cast(:items as jsonb), updated_at = :now, updated_by = :actor,
                           version = version + 1
                     where kind = :kind and version = :version""")
                    .param("items", doc).param("now", now).param("actor", actor).param("kind", kind.code())
                    .param("version", current.version()).update();
        }
        if (changed == 0) {
            throw new ApiException(org.springframework.http.HttpStatus.PRECONDITION_FAILED, "version_conflict",
                    "Справочник изменён другим пользователем. Обновите и повторите");
        }
        var saved = new Dictionary(kind.code(), clean, current.version() + 1);
        versions.record("admin.dictionary", kind.code(), label(kind), "update", saved, comment);
        return saved;
    }

    static String label(Kind kind) {
        return switch (kind) {
            case RESOLUTION -> "Коды решения";
            case PENDING -> "Причины ожидания";
            case CANCEL -> "Причины отмены";
        };
    }

    static List<DictionaryItem> validate(List<DictionaryItem> previous, List<DictionaryItem> items) {
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("empty_dictionary", "В справочнике должно быть хотя бы одно значение");
        }
        if (items.size() > MAX_ITEMS) {
            throw ApiException.badRequest("too_many_items", "Не больше " + MAX_ITEMS + " значений");
        }
        Set<String> seen = new HashSet<>();
        List<DictionaryItem> clean = new java.util.ArrayList<>();
        for (DictionaryItem i : items) {
            String code = i.code() == null ? "" : i.code().trim();
            String name = i.name() == null ? "" : i.name().trim();
            if (!CODE.matcher(code).matches()) {
                throw ApiException.badRequest("invalid_code",
                        "Код «" + code + "»: латиница в нижнем регистре, цифры и _, до 40 символов");
            }
            if (name.isEmpty() || name.length() > 100) {
                throw ApiException.badRequest("invalid_name", "Название кода «" + code + "»: от 1 до 100 символов");
            }
            if (!seen.add(code)) {
                throw ApiException.badRequest("duplicate_code", "Код «" + code + "» повторяется");
            }
            clean.add(new DictionaryItem(code, name, i.active()));
        }
        for (DictionaryItem p : previous) {
            if (!seen.contains(p.code())) {
                throw ApiException.conflict("code_removed",
                        "Код «" + p.code() + "» уже мог попасть в заявки: выключите его вместо удаления");
            }
        }
        if (clean.stream().noneMatch(DictionaryItem::active)) {
            throw ApiException.badRequest("no_active_items", "Оставьте включённым хотя бы одно значение");
        }
        return List.copyOf(clean);
    }
}
