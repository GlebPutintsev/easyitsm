package ru.easyitsm.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.easyitsm.identity.api.RequiresRecentAuth;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;

/**
 * Повторная аутентификация перед опасными действиями (SEC-08), см. {@link RequiresRecentAuth}.
 * Запросы со входом для разработки (заголовки X-Dev-*, только профили dev и test) сессии не имеют и проверку
 * проходят: так модули тестируют свои эндпоинты без полного входа.
 */
@Configuration(proxyBeanMethods = false)
class ReauthInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final IdentityProperties props;

    ReauthInterceptor(IdentityProperties props) {
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !requiresReauth(method)) {
            return true;
        }
        if (CurrentActor.get().isEmpty()) {
            return true; // доступ без входа отсечёт Spring Security
        }
        var session = SessionInfo.current();
        if (session.isEmpty()) {
            return true; // вход для разработки
        }
        Instant reauthAt = session.get().reauthAt();
        if (reauthAt == null || reauthAt.plus(props.session().reauthWindow()).isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "reauth_required",
                    "Подтвердите вход, чтобы выполнить это действие");
        }
        return true;
    }

    private static boolean requiresReauth(HandlerMethod method) {
        return method.hasMethodAnnotation(RequiresRecentAuth.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), RequiresRecentAuth.class);
    }
}
