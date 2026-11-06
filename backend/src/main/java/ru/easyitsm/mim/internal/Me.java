package ru.easyitsm.mim.internal;

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

    /** Объявляют крупный инцидент сразу и согласуют объявление агента (3.2). */
    static final Set<String> DECLARERS = Set.of("LEAD", "MIM");
    /** Поддержка: видит командный центр, объявляет с согласованием. */
    static final Set<String> SUPPORT = Set.of("AGT", "LEAD", "MIM", "CHG", "PRB", "CFG", "AST");

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
