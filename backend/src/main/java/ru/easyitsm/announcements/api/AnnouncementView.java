package ru.easyitsm.announcements.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AnnouncementView(
        UUID id,
        String kind,
        String title,
        String body,
        Instant startsAt,
        Instant endsAt,
        boolean published,
        List<UUID> serviceIds,
        Instant updatedAt,
        long version) {
}
