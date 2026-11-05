package ru.easyitsm.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Идентификатор трассировки запроса: из W3C {@code traceparent} или новый.
 * Попадает в логи (MDC {@code trace_id}), в заголовок {@code X-Trace-Id} и в ошибки RFC 9457.
 * TODO(devsecops): заменить на OpenTelemetry, сохранив поле trace_id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "trace_id";
    public static final String HEADER = "X-Trace-Id";
    private static final Pattern TRACEPARENT = Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = fromTraceparent(request.getHeader("traceparent"));
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : "none";
    }

    private static String fromTraceparent(String header) {
        if (header != null) {
            var m = TRACEPARENT.matcher(header);
            if (m.matches()) {
                return m.group(1);
            }
        }
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
