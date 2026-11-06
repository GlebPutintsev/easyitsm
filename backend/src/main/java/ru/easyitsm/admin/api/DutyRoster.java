package ru.easyitsm.admin.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Дежурства групп (10.16 «Назначить дежурство»): кто принимает заявки группы в заданный момент. */
public interface DutyRoster {

    /** Дежурные группы в момент {@code at}, по началу смены. */
    List<UUID> onDuty(UUID groupId, Instant at);
}
