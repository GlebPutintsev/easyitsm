package ru.easyitsm.admin.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.admin.api.DutyRoster;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.platform.web.ApiException;

/** Дежурства групп (10.16 «Группы и графики → Назначить дежурство»). Дежурить может только участник группы. */
@Service
class DutyService implements DutyRoster {

    static final Duration MAX_SHIFT = Duration.ofDays(31);
    static final Duration MAX_RANGE = Duration.ofDays(92);

    private final DutyShiftRepository repository;
    private final UserDirectory directory;
    private final ConfigVersionService versions;

    DutyService(DutyShiftRepository repository, UserDirectory directory, ConfigVersionService versions) {
        this.repository = repository;
        this.directory = directory;
        this.versions = versions;
    }

    record Create(@NotNull UUID groupId, @NotNull UUID userId, @NotNull Instant startsAt, @NotNull Instant endsAt,
                  @Size(max = 500) String note) {
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> onDuty(UUID groupId, Instant at) {
        return repository.overlapping(groupId, at, at.plusNanos(1000)).stream()
                .map(s -> s.toView().userId()).distinct().toList();
    }

    @Transactional(readOnly = true)
    List<DutyShift.View> list(UUID groupId, Instant from, Instant to) {
        requireGroup(groupId);
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw ApiException.badRequest("invalid_range", "Период — до 92 дней, окончание позже начала");
        }
        return repository.overlapping(groupId, from, to).stream().map(DutyShift::toView).toList();
    }

    @Transactional
    DutyShift.View create(Create cmd) {
        requireGroup(cmd.groupId());
        if (!cmd.endsAt().isAfter(cmd.startsAt())) {
            throw ApiException.badRequest("invalid_window", "Окончание смены должно быть позже начала");
        }
        if (Duration.between(cmd.startsAt(), cmd.endsAt()).compareTo(MAX_SHIFT) > 0) {
            throw ApiException.badRequest("shift_too_long", "Смена не длиннее 31 дня");
        }
        if (!directory.isMember(cmd.groupId(), cmd.userId())) {
            throw ApiException.badRequest("not_a_member", "Дежурить может только участник группы");
        }
        DutyShift saved = repository.save(new DutyShift(cmd.groupId(), cmd.userId(), cmd.startsAt(), cmd.endsAt(),
                cmd.note() == null ? "" : cmd.note().trim()));
        versions.record("admin.duty", cmd.groupId().toString(), groupName(cmd.groupId()), "create", saved.toView(),
                null);
        return saved.toView();
    }

    @Transactional
    void delete(UUID id) {
        DutyShift shift = repository.findById(id).orElseThrow(() -> ApiException.notFound("Смена"));
        repository.delete(shift);
        versions.record("admin.duty", shift.groupId().toString(), groupName(shift.groupId()), "delete",
                shift.toView(), null);
    }

    private void requireGroup(UUID groupId) {
        if (directory.findGroup(groupId).isEmpty()) {
            throw ApiException.notFound("Группа");
        }
    }

    private String groupName(UUID groupId) {
        return directory.findGroup(groupId).map(g -> g.name()).orElse("");
    }
}
