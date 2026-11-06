package ru.easyitsm.changes.internal;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;

/** Текущий пользователь и его роли раздела 3 ТЗ. */
record Me(UUID userId, Set<String> roles) {

    /** Роли поддержки, PRB, CFG и AST наследуют матрицу AGT. */
    static final Set<String> SUPPORT = Set.of("AGT", "LEAD", "MIM", "CHG", "PRB", "CFG", "AST");

    static Me current() {
        UUID user = CurrentActor.userId()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Требуется вход"));
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .collect(Collectors.toUnmodifiableSet());
        return new Me(user, roles);
    }

    boolean has(String role) {
        return roles.contains(role);
    }

    boolean manager() {
        return has("CHG");
    }

    boolean support() {
        return roles.stream().anyMatch(SUPPORT::contains);
    }

    boolean is(UUID id) {
        return id != null && id.equals(userId);
    }
}
