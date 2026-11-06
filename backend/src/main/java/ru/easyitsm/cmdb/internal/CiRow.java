package ru.easyitsm.cmdb.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import ru.easyitsm.cmdb.api.CiCriticality;
import ru.easyitsm.cmdb.api.CiLayer;
import ru.easyitsm.cmdb.api.CiRef;
import ru.easyitsm.cmdb.api.CiStatus;

/** Строка таблицы {@code ci} с кодом, названием и уровнем класса. */
record CiRow(
        UUID id,
        UUID classId,
        String classCode,
        String className,
        CiLayer layer,
        String name,
        CiStatus status,
        String environment,
        CiCriticality criticality,
        UUID ownerGroupId,
        String description,
        Map<String, Object> attributes,
        Map<String, Object> attributeSources,
        Instant lastSeenAt,
        Instant createdAt,
        UUID createdBy,
        Instant updatedAt,
        UUID updatedBy,
        long version,
        UUID mergedInto) {

    CiRef ref() {
        return new CiRef(id, name, classCode, className, layer, status, environment, criticality, ownerGroupId,
                version);
    }
}
