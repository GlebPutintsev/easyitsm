package ru.easyitsm.announcements.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class AnnouncementCommands {

    private AnnouncementCommands() {
    }

    record Create(
            @NotNull @Pattern(regexp = "info|maintenance|outage") String kind,
            @NotBlank @Size(max = 255) String title,
            @Size(max = 10_000) String body,
            @NotNull Instant startsAt,
            Instant endsAt,
            boolean published,
            @Size(max = 50) List<@NotNull UUID> serviceIds) {
    }

    /** Частичное изменение: null означает «не менять». */
    record Update(
            @Pattern(regexp = "info|maintenance|outage") String kind,
            @Size(min = 1, max = 255) String title,
            @Size(max = 10_000) String body,
            Instant startsAt,
            Instant endsAt,
            Boolean published,
            @Size(max = 50) List<@NotNull UUID> serviceIds) {
    }
}
