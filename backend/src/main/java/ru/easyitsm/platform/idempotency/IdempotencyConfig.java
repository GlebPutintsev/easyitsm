package ru.easyitsm.platform.idempotency;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class IdempotencyConfig {

    /** Порядок 0: после цепочки Spring Security (-100), когда пользователь и арендатор уже известны. */
    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(JdbcClient jdbc) {
        var registration = new FilterRegistrationBean<>(new IdempotencyFilter(jdbc));
        registration.setOrder(0);
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}
