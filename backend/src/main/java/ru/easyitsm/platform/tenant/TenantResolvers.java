package ru.easyitsm.platform.tenant;

import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.easyitsm.platform.security.ActorPrincipal;

@Configuration(proxyBeanMethods = false)
class TenantResolvers {

    /** Арендатор из аутентифицированного пользователя. Основной источник. */
    @Bean
    @Order(100)
    TenantResolver principalTenantResolver() {
        return request -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof ActorPrincipal actor) {
                return Optional.of(actor.tenantId());
            }
            return Optional.empty();
        };
    }

    /** В режиме SINGLE у инсталляции один арендатор. */
    @Bean
    @Order(1000)
    TenantResolver singleTenantResolver(TenancyProperties props) {
        return request -> props.mode() == TenancyProperties.Mode.SINGLE
                ? Optional.ofNullable(props.defaultTenantId())
                : Optional.empty();
    }
}
