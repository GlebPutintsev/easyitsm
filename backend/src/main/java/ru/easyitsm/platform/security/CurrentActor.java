package ru.easyitsm.platform.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Доступ к текущему пользователю без зависимости от модуля identity. */
public final class CurrentActor {

    private CurrentActor() {
    }

    public static Optional<ActorPrincipal> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ActorPrincipal actor) {
            return Optional.of(actor);
        }
        return Optional.empty();
    }

    public static Optional<UUID> userId() {
        return get().map(ActorPrincipal::userId);
    }
}
