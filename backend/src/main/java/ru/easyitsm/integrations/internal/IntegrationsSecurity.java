package ru.easyitsm.integrations.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import ru.easyitsm.platform.security.SecurityChainCustomizer;

/**
 * Приём событий мониторинга открыт без входа: системы мониторинга предъявляют токен источника, который
 * проверяет сам контроллер. Куки сессии у них нет, поэтому CSRF к запросу не применяется.
 */
@Configuration(proxyBeanMethods = false)
class IntegrationsSecurity {

    @Bean
    SecurityChainCustomizer integrationsSecurityCustomizer() {
        return http -> http.authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, InboundEventsController.PATH).permitAll());
    }
}
