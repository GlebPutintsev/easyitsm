package ru.easyitsm.catalog.internal;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;

/** Роли и пользователь текущего запроса. */
final class Roles {

    /** Роли, которые видят маршруты согласования любых заявок своей области. */
    static final Set<String> ROUTE_READERS = Set.of("AGT", "LEAD", "MIM", "ADM", "AUD");

    private Roles() {
    }

    static Set<String> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Set.of();
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .collect(Collectors.toUnmodifiableSet());
    }

    static boolean admin() {
        return current().contains("ADM");
    }

    static UUID actor() {
        return CurrentActor.userId().orElseThrow(ApiException::forbidden);
    }
}
