package ru.easyitsm.admin.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.admin.api.OrgUnitSummary;
import ru.easyitsm.admin.api.OrgUnits;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;

/** Организации и подразделения (ADM-02). Записи не удаляются: на них ссылаются пользователи, их выключают. */
@Service
class OrgUnitService implements OrgUnits {

    private final OrgUnitRepository repository;
    private final ConfigVersionService versions;

    OrgUnitService(OrgUnitRepository repository, ConfigVersionService versions) {
        this.repository = repository;
        this.versions = versions;
    }

    record Create(@NotNull @Pattern(regexp = "organization|department") String kind,
                  @NotBlank @Size(max = 255) String name,
                  @Size(max = 64) String code,
                  UUID parentId) {
    }

    /** Частичное изменение: null — не менять; {@code clearParent} убирает вышестоящее подразделение. */
    record Update(@Size(min = 1, max = 255) String name, @Size(max = 64) String code, UUID parentId,
                  Boolean clearParent, Boolean active) {
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrgUnitSummary> findAll(Collection<UUID> ids) {
        return repository.findAllById(ids).stream().map(OrgUnit::toSummary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrgUnitSummary> list(String kind) {
        return repository.search(kind, null).stream().map(OrgUnit::toSummary).toList();
    }

    @Transactional(readOnly = true)
    List<OrgUnit.View> search(String kind, String q) {
        String pattern = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return repository.search(kind == null || kind.isBlank() ? null : kind, pattern).stream()
                .map(OrgUnit::toView).toList();
    }

    @Transactional(readOnly = true)
    OrgUnit.View get(UUID id) {
        return find(id).toView();
    }

    @Transactional
    OrgUnit.View create(Create cmd) {
        if (cmd.parentId() != null) {
            find(cmd.parentId());
        }
        var unit = new OrgUnit(cmd.kind(), cmd.name().trim(), blankToNull(cmd.code()), cmd.parentId());
        OrgUnit saved = Unique.guard(() -> repository.save(unit), "name_taken",
                "Такое название или код уже есть");
        versions.record("admin.org_unit", saved.getId().toString(), saved.name(), "create", saved.toView(), null);
        return saved.toView();
    }

    @Transactional
    OrgUnit.View update(UUID id, String ifMatch, Update cmd) {
        OrgUnit unit = find(id);
        ETags.requireMatch(ifMatch, unit.getVersion());
        if (cmd.name() != null) {
            unit.setName(cmd.name().trim());
        }
        if (cmd.code() != null) {
            unit.setCode(blankToNull(cmd.code()));
        }
        if (Boolean.TRUE.equals(cmd.clearParent())) {
            unit.setParentId(null);
        } else if (cmd.parentId() != null) {
            requireNoCycle(id, cmd.parentId());
            unit.setParentId(cmd.parentId());
        }
        if (cmd.active() != null) {
            unit.setActive(cmd.active());
        }
        OrgUnit saved = Unique.guard(() -> repository.save(unit), "name_taken", "Такое название или код уже есть");
        versions.record("admin.org_unit", id.toString(), saved.name(), "update", saved.toView(), null);
        return saved.toView();
    }

    /** Подразделение по имени для импорта: создаёт его, если нет. */
    @Transactional
    OrgUnit findOrCreateDepartment(String name, List<UUID> created) {
        return repository.findByName("department", name.trim()).orElseGet(() -> {
            OrgUnit saved = repository.save(new OrgUnit("department", name.trim(), null, null));
            created.add(saved.getId());
            return saved;
        });
    }

    java.util.Optional<OrgUnit> findByName(String kind, String name) {
        return repository.findByName(kind, name.trim());
    }

    OrgUnit save(OrgUnit unit) {
        return Unique.guard(() -> repository.save(unit), "name_taken", "Такое название или код уже есть");
    }

    void delete(UUID id) {
        repository.deleteById(id);
    }

    private void requireNoCycle(UUID id, UUID parentId) {
        Set<UUID> seen = new HashSet<>();
        UUID cur = parentId;
        while (cur != null) {
            if (cur.equals(id) || !seen.add(cur)) {
                throw ApiException.badRequest("parent_cycle", "Подразделение не может входить само в себя");
            }
            cur = find(cur).parentId();
        }
    }

    private OrgUnit find(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Подразделение"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
