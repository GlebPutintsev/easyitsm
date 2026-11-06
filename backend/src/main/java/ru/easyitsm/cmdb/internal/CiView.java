package ru.easyitsm.cmdb.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** КЕ в REST. Коды перечислений — строчными буквами, как во всём API. */
record CiView(
        UUID id,
        String name,
        String classCode,
        String className,
        String layer,
        String status,
        String environment,
        String criticality,
        UUID ownerGroupId,
        String description,
        Map<String, Object> attributes,
        Map<String, Object> attributeSources,
        Instant lastSeenAt,
        Instant createdAt,
        UUID createdBy,
        Instant updatedAt,
        UUID updatedBy,
        long version) {

    static CiView of(CiRow r) {
        return new CiView(r.id(), r.name(), r.classCode(), r.className(), r.layer().code(), r.status().code(),
                r.environment(), r.criticality().code(), r.ownerGroupId(), r.description(), r.attributes(),
                r.attributeSources(), r.lastSeenAt(), r.createdAt(), r.createdBy(), r.updatedAt(), r.updatedBy(),
                r.version());
    }
}
