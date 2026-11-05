package ru.easyitsm.platform.persistence;

import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jdbc.repository.config.EnableJdbcAuditing;
import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;

@Configuration(proxyBeanMethods = false)
@EnableJdbcAuditing(auditorAwareRef = "currentActorAuditor")
class PersistenceConfig {

    @Bean
    AuditorAware<UUID> currentActorAuditor() {
        return CurrentActor::userId;
    }

    /** Новая запись получает арендатора из контекста. Чужого арендатора выставить нельзя: RLS отклонит запись. */
    @Bean
    BeforeConvertCallback<TenantEntity> tenantAssigningCallback() {
        return entity -> {
            if (entity.getTenantId() == null) {
                entity.setTenantId(TenantContext.require());
            }
            return entity;
        };
    }
}
