package ru.easyitsm.announcements.api;

import java.time.Instant;
import java.util.UUID;

public record AnnouncementView(
        UUID id,
        String kind,
        String title,
        String body,
        Instant startsAt,
        Instant endsAt,
        boolean published,
        Instant updatedAt,
        long version) {
}
