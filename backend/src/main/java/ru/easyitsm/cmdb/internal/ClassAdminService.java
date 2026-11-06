package ru.easyitsm.cmdb.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;

/**
 * Настройка классов КЕ и типов связей (CFG-01, CFG-03): свои классы и атрибуты без изменения кода.
 * Код класса и его родитель после создания не меняются, чтобы не ломать наследование и загрузку из источников.
 */
@Service
class ClassAdminService {

    static final String CODE = "[a-z][a-z0-9_]{1,62}";

    record CreateClass(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 255) String name,
                       String parentCode,
                       @Pattern(regexp = "business_service|technical_service|application|infrastructure") String layer,
                       Boolean isAbstract, List<AttributeDef> attributes, List<List<String>> identificationRules) {
    }

    /** Частичное изменение: null — не менять. {@code attributes} — собственные атрибуты класса целиком. */
    record UpdateClass(@Size(min = 1, max = 255) String name, Boolean active, Boolean isAbstract,
                       List<AttributeDef> attributes, List<List<String>> identificationRules) {
    }

    record CreateRelationType(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 255) String name,
                              @NotBlank @Size(max = 255) String inverseName,
                              @NotBlank @Pattern(regexp = "target_to_source|source_to_target|none")
                              String impactDirection) {
    }

    record UpdateRelationType(@Size(min = 1, max = 255) String name, @Size(min = 1, max = 255) String inverseName,
                              @Pattern(regexp = "target_to_source|source_to_target|none") String impactDirection,
                              Boolean active) {
    }

    private final JdbcClient jdbc;
    private final CmdbJson json;
    private final CiClasses classes;

    ClassAdminService(JdbcClient jdbc, CmdbJson json, CiClasses classes) {
        this.jdbc = jdbc;
        this.json = json;
        this.classes = classes;
    }

    @Transactional
    public UUID createClass(CreateClass cmd) {
        ClassTree tree = classes.tree();
        if (tree.byCode(cmd.code()).isPresent()) {
            throw CmdbProblem.clash("class_exists", "Класс с таким кодом уже есть");
        }
        ClassTree.ClassInfo parent = null;
        if (cmd.parentCode() != null) {
            parent = tree.byCode(cmd.parentCode()).orElseThrow(
                    () -> CmdbProblem.invalid("unknown_class", "parentCode", "Родительский класс не найден"));
        }
        String layer = parent != null ? parent.layer().code() : cmd.layer();
        if (layer == null) {
            throw CmdbProblem.invalid("field_required", "layer", "Укажите уровень сервисной модели или родителя");
        }
        if (parent != null && cmd.layer() != null && !cmd.layer().equals(layer)) {
            throw CmdbProblem.invalid("invalid_field", "layer", "Подкласс наследует уровень родителя");
        }
        List<AttributeDef> own = cmd.attributes() == null ? List.of() : cmd.attributes();
        List<List<String>> rules = cmd.identificationRules() == null ? List.of() : cmd.identificationRules();
        List<AttributeDef> inherited = parent == null ? List.of() : tree.effectiveAttributes(parent);
        validate(own, inherited, rules);
        UUID id = Ids.newId();
        UUID actor = CurrentActor.userId().orElse(null);
        jdbc.sql("""
                insert into ci_class (id, tenant_id, parent_id, code, name, layer, abstract, system, attributes,
                                      identification_rules, created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :parent, :code, :name, :layer, :abstract, false, cast(:attrs as jsonb),
                        cast(:rules as jsonb), now(), :actor, now(), :actor, 0)""")
                .param("id", id).param("tenant", TenantContext.require())
                .param("parent", parent == null ? null : parent.id()).param("code", cmd.code())
                .param("name", cmd.name().trim()).param("layer", layer).param("abstract", Boolean.TRUE.equals(cmd.isAbstract()))
                .param("attrs", json.write(own)).param("rules", json.write(rules)).param("actor", actor)
                .update();
        return id;
    }

    @Transactional
    public void updateClass(String code, String ifMatch, UpdateClass cmd) {
        ClassTree tree = classes.tree();
        ClassTree.ClassInfo cls = tree.byCode(code).orElseThrow(() -> ApiException.notFound("Класс КЕ"));
        ETags.requireMatch(ifMatch, cls.version());
        List<AttributeDef> own = cmd.attributes() != null ? cmd.attributes() : cls.attributes();
        List<List<String>> rules = cmd.identificationRules() != null ? cmd.identificationRules() : cls.rules();
        List<AttributeDef> inherited = cls.parentId() == null ? List.of()
                : tree.effectiveAttributes(tree.byId(cls.parentId()).orElseThrow());
        if (cls.system() && cmd.attributes() != null) {
            // Системные атрибуты нужны сверке и агенту обнаружения: их можно дополнять, но не убирать.
            Set<String> kept = new HashSet<>();
            own.forEach(a -> kept.add(a.code()));
            for (AttributeDef a : cls.attributes()) {
                if (!kept.contains(a.code())) {
                    throw CmdbProblem.invalid("system_attribute", a.code(),
                            "Атрибут «" + a.name() + "» стандартного класса нельзя удалить");
                }
            }
        }
        validate(own, inherited, rules);
        if (Boolean.FALSE.equals(cmd.active()) && cls.system()) {
            throw CmdbProblem.invalid("system_class", "active", "Стандартный класс нельзя отключить");
        }
        if (cmd.isAbstract() != null && cmd.isAbstract() && !cls.isAbstract()) {
            Integer used = jdbc.sql("select count(*) from ci where class_id = :id").param("id", cls.id())
                    .query(Integer.class).single();
            if (used > 0) {
                throw CmdbProblem.invalid("class_in_use", "isAbstract", "В классе уже есть КЕ");
            }
        }
        jdbc.sql("""
                update ci_class set name = :name, active = :active, abstract = :abstract,
                                    attributes = cast(:attrs as jsonb), identification_rules = cast(:rules as jsonb),
                                    updated_at = now(), updated_by = :actor, version = version + 1
                 where id = :id""")
                .param("id", cls.id())
                .param("name", cmd.name() != null ? cmd.name().trim() : cls.name())
                .param("active", cmd.active() != null ? cmd.active() : cls.active())
                .param("abstract", cmd.isAbstract() != null ? cmd.isAbstract() : cls.isAbstract())
                .param("attrs", json.write(own)).param("rules", json.write(rules))
                .param("actor", CurrentActor.userId().orElse(null))
                .update();
    }

    @Transactional
    public CiClasses.RelationType createRelationType(CreateRelationType cmd) {
        if (classes.relationTypes().stream().anyMatch(t -> t.code().equals(cmd.code()))) {
            throw CmdbProblem.clash("relation_type_exists", "Тип связи с таким кодом уже есть");
        }
        UUID id = Ids.newId();
        UUID actor = CurrentActor.userId().orElse(null);
        jdbc.sql("""
                insert into ci_relation_type (id, tenant_id, code, name, inverse_name, impact_direction, system,
                                              created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :code, :name, :inverse, :impact, false, now(), :actor, now(), :actor, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).param("code", cmd.code())
                .param("name", cmd.name().trim()).param("inverse", cmd.inverseName().trim())
                .param("impact", cmd.impactDirection()).param("actor", actor)
                .update();
        return new CiClasses.RelationType(id, cmd.code(), cmd.name().trim(), cmd.inverseName().trim(),
                cmd.impactDirection(), false, true);
    }

    @Transactional
    public CiClasses.RelationType updateRelationType(String code, UpdateRelationType cmd) {
        CiClasses.RelationType t = classes.relationTypes().stream().filter(r -> r.code().equals(code)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Тип связи"));
        if (t.system() && (Boolean.FALSE.equals(cmd.active())
                || cmd.impactDirection() != null && !cmd.impactDirection().equals(t.impactDirection()))) {
            throw CmdbProblem.invalid("system_relation_type", "impactDirection",
                    "У стандартного типа связи можно менять только названия");
        }
        var next = new CiClasses.RelationType(t.id(), t.code(), cmd.name() != null ? cmd.name().trim() : t.name(),
                cmd.inverseName() != null ? cmd.inverseName().trim() : t.inverseName(),
                cmd.impactDirection() != null ? cmd.impactDirection() : t.impactDirection(), t.system(),
                cmd.active() != null ? cmd.active() : t.active());
        jdbc.sql("""
                update ci_relation_type set name = :name, inverse_name = :inverse, impact_direction = :impact,
                                            active = :active, updated_at = now(), updated_by = :actor,
                                            version = version + 1
                 where id = :id""")
                .param("id", t.id()).param("name", next.name()).param("inverse", next.inverseName())
                .param("impact", next.impactDirection()).param("active", next.active())
                .param("actor", CurrentActor.userId().orElse(null))
                .update();
        return next;
    }

    /** Атрибуты: код, тип, без повторов и без «секретных» имён (CFG-08); правила — из известных атрибутов. */
    private static void validate(List<AttributeDef> own, List<AttributeDef> inherited, List<List<String>> rules) {
        Set<String> codes = new HashSet<>();
        inherited.forEach(a -> codes.add(a.code()));
        Set<String> ownCodes = new HashSet<>();
        for (AttributeDef a : own) {
            if (a.code() == null || !a.code().matches(CODE)) {
                throw CmdbProblem.invalid("invalid_attribute_def", "attributes",
                        "Код атрибута: латиница, цифры и _, с буквы");
            }
            if (!ownCodes.add(a.code())) {
                throw CmdbProblem.invalid("duplicate_attribute", a.code(), "Атрибут указан дважды");
            }
            if (a.name() == null || a.name().isBlank() || a.name().length() > 255) {
                throw CmdbProblem.invalid("invalid_attribute_def", a.code(), "Укажите название атрибута");
            }
            if (!AttributeDef.TYPES.contains(a.type())) {
                throw CmdbProblem.invalid("invalid_attribute_def", a.code(), "Тип атрибута: " + AttributeDef.TYPES);
            }
            if ("enum".equals(a.type()) && a.options().isEmpty()) {
                throw CmdbProblem.invalid("invalid_attribute_def", a.code(), "Для списка нужны значения");
            }
            if (Attributes.SECRET_NAME.matcher(a.code()).matches()
                    || Attributes.SECRET_NAME.matcher(a.name()).matches()) {
                throw CmdbProblem.invalid("secret_not_allowed", a.code(),
                        "В CMDB нельзя хранить пароли, токены и ключи (CFG-08)");
            }
            codes.add(a.code());
        }
        for (List<String> rule : rules) {
            if (rule == null || rule.isEmpty()) {
                throw CmdbProblem.invalid("invalid_rule", "identificationRules", "Пустое правило идентификации");
            }
            for (String attr : rule) {
                if (!codes.contains(attr)) {
                    throw CmdbProblem.invalid("invalid_rule", "identificationRules",
                            "Правило ссылается на неизвестный атрибут «" + attr + "»");
                }
            }
        }
    }
}
