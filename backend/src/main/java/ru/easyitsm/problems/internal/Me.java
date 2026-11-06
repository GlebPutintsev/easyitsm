package ru.easyitsm.problems.internal;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;

/** Текущий пользователь запроса: id и роли раздела 3 ТЗ. */
final class Me {

    /** Ведут проблемы и публикуют известные ошибки. */
    static final Set<String> PROBLEM_MANAGERS = Set.of("PRB", "LEAD", "MIM");

    private Me() {
    }

    static UUID id() {
        return CurrentActor.userId()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Требуется вход"));
    }

    static Set<String> roles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Set.of();
        }
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5))
                .collect(Collectors.toUnmodifiableSet());
    }

    static boolean hasAny(Set<String> roles) {
        return roles().stream().anyMatch(roles::contains);
    }

    static boolean hasAny(String... roles) {
        return hasAny(Set.of(roles));
    }

    static void requireAny(Set<String> roles) {
        if (!hasAny(roles)) {
            throw ApiException.forbidden();
        }
    }

    static String joined(String... roles) {
        return Arrays.stream(roles).collect(Collectors.joining(","));
    }
}
