package ru.easyitsm.cmdb.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

final class CiCommands {

    static final String STATUS = "planned|active|maintenance|retired";
    static final String CRITICALITY = "low|medium|high|critical";
    static final String ENVIRONMENT = "[a-z][a-z0-9_]{0,31}";

    private CiCommands() {
    }

    record Create(
            @NotBlank @Size(max = 255) String name,
            @NotBlank String classCode,
            @Pattern(regexp = STATUS) String status,
            @Pattern(regexp = ENVIRONMENT) String environment,
            @Pattern(regexp = CRITICALITY) String criticality,
            UUID ownerGroupId,
            @Size(max = 10_000) String description,
            Map<String, Object> attributes) {
    }

    /**
     * Частичное изменение (JSON Merge Patch, RFC 7396): отсутствующее поле не меняется, {@code null} очищает
     * среду, группу-владельца и атрибут. В {@code attributes} передаются только изменяемые атрибуты.
     */
    record Update(Map<String, Object> fields) {

        boolean has(String name) {
            return fields.containsKey(name);
        }

        Object get(String name) {
            return fields.get(name);
        }
    }

    record AddRelation(@NotBlank String typeCode, UUID targetId,
                       @Pattern(regexp = "outgoing|incoming") String direction) {
    }
}
