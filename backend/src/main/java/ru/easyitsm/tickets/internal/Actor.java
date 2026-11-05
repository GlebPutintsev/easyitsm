package ru.easyitsm.tickets.internal;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;

/**
 * Кто выполняет действие: пользователь с ролями раздела 3 ТЗ или система (вызов из другого модуля,
 * таймер). Система проходит проверки прав, но не проверки данных.
 */
record Actor(UUID userId, Set<String> roles, boolean system) {

    /** Роли поддержки: обрабатывают заявки, видят внутренние заметки. PRB, AST наследуют матрицу AGT. */
    static final Set<String> SUPPORT_ROLES = Set.of("AGT", "LEAD", "MIM", "CHG", "PRB", "CFG", "AST");

    static Actor current() {
        UUID user = CurrentActor.userId().orElseThrow(() -> new ApiException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "unauthorized", "Требуется вход"));
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .collect(Collectors.toUnmodifiableSet());
        return new Actor(user, roles, false);
    }

    static Actor system(UUID onBehalfOf) {
        return new Actor(onBehalfOf, Set.of("system"), true);
    }

    boolean has(String role) {
        return roles.contains(role);
    }

    boolean isSupport() {
        return system || roles.stream().anyMatch(SUPPORT_ROLES::contains);
    }

    boolean canSeeInternal() {
        return isSupport() || has("AUD");
    }

    boolean isAdmin() {
        return system || has("ADM");
    }

    /** Может создавать заявки: все роли, кроме чистого аудитора. */
    boolean canCreate() {
        return system || !roles.equals(Set.of("AUD"));
    }

    boolean is(UUID user) {
        return userId != null && userId.equals(user);
    }
}
