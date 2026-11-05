package ru.easyitsm.identity.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Сведения о сессии в {@code Authentication#getDetails()} для запросов, вошедших по куке. */
record SessionInfo(UUID sessionId, String authMethod, Instant createdAt, Instant reauthAt) {

    static Optional<SessionInfo> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof SessionInfo info) {
            return Optional.of(info);
        }
        return Optional.empty();
    }
}
