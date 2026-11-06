package ru.easyitsm.audit.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.easyitsm.audit.api.AuditCategory;
import ru.easyitsm.audit.api.AuditEntry;
import ru.easyitsm.audit.api.AuditResult;
import ru.easyitsm.identity.api.RequiresRecentAuth;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Журнал по ответу API, без правок в модулях:
 * <ul>
 *   <li>403 и 404 на запрос к записи — «отказ в доступе» (AT-01: чужая заявка по ссылке, AT-05: автор
 *       утверждает своё изменение). 404 пишется, только если в маршруте есть идентификатор записи;</li>
 *   <li>действие с {@link RequiresRecentAuth} — «опасное действие» с результатом (SEC-08);</li>
 *   <li>успешное изменение настроек по {@link AuditRoutes} (SEC-29).</li>
 * </ul>
 * Тело запроса не пишется: в нём могут быть пароли и персональные данные. Ошибка записи в журнал не ломает
 * ответ пользователю, но попадает в лог с трассировкой.
 */
@Configuration(proxyBeanMethods = false)
class ApiAuditInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ApiAuditInterceptor.class);

    private final AuditWriter writer;

    ApiAuditInterceptor(AuditWriter writer) {
        this.writer = writer;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Первым: afterCompletion вызывается, даже если следующий перехватчик (повторный вход) отказал.
        registry.addInterceptor(this).addPathPatterns("/api/**").order(Ordered.HIGHEST_PRECEDENCE);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        if (!(handler instanceof HandlerMethod method) || TenantContext.current().isEmpty()) {
            return;
        }
        try {
            entryFor(request, response.getStatus(), method).ifPresent(writer::recordSeparately);
        } catch (RuntimeException e) {
            log.error("Не удалось записать событие в журнал аудита", e);
        }
    }

    static Optional<AuditEntry> entryFor(HttpServletRequest request, int status, HandlerMethod method) {
        String httpMethod = request.getMethod();
        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Map<String, String> vars = pathVariables(request);
        boolean privileged = method.hasMethodAnnotation(RequiresRecentAuth.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), RequiresRecentAuth.class);
        Optional<String> configArea = AuditRoutes.configArea(httpMethod, pattern);

        AuditEntry entry;
        if (status == 403 || (status == 404 && !vars.isEmpty())) {
            entry = AuditEntry.of(AuditCategory.ACCESS, "access.denied").result(AuditResult.DENIED);
        } else if (status == 401) {
            return Optional.empty(); // нет входа или нужен повторный вход: клиент повторит запрос
        } else if (privileged) {
            entry = AuditEntry.of(configArea.isPresent() ? AuditCategory.CONFIG : AuditCategory.SECURITY,
                    "security.privileged_action").result(status < 400 ? AuditResult.SUCCESS : AuditResult.FAILURE);
        } else if (configArea.isPresent() && status < 400) {
            entry = AuditEntry.of(AuditCategory.CONFIG, "config.change").detail("area", configArea.get());
        } else {
            return Optional.empty();
        }
        return Optional.of(entry
                .object(AuditRoutes.objectType(pattern), objectId(vars))
                .detail("method", httpMethod)
                .detail("route", pattern)
                .detail("status", status)
                .detail("handler", method.getBeanType().getSimpleName() + "." + method.getMethod().getName()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return vars instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

    private static String objectId(Map<String, String> vars) {
        if (vars.containsKey("id")) {
            return vars.get("id");
        }
        if (vars.containsKey("ref")) {
            return vars.get("ref");
        }
        return vars.values().stream().findFirst().orElse(null);
    }
}
