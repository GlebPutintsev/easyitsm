package ru.easyitsm.admin.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;

@Table("duty_shift")
class DutyShift extends TenantEntity {

    private UUID groupId;
    private UUID userId;
    private Instant startsAt;
    private Instant endsAt;
    private String note = "";

    protected DutyShift() {
    }

    DutyShift(UUID groupId, UUID userId, Instant startsAt, Instant endsAt, String note) {
        this.groupId = groupId;
        this.userId = userId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.note = note == null ? "" : note;
    }

    UUID groupId() {
        return groupId;
    }

    View toView() {
        return new View(getId(), groupId, userId, startsAt, endsAt, note, getVersion());
    }

    record View(UUID id, UUID groupId, UUID userId, Instant startsAt, Instant endsAt, String note, long version) {
    }
}
