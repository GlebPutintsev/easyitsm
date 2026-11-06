package ru.easyitsm.integrations.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Приём событий мониторинга ({@code POST /api/v1/integrations/events}, раздел 8.2 ТЗ). Вход — только токен
 * источника в {@code Authorization: Bearer …}: без сессии и CSRF, арендатор определяется по токену.
 * <p>
 * Ответ 202 с итогом по каждому алерту. 401 — нет или неверный токен, 403 — источник выключен, 413 — тело
 * больше {@code easyitsm.integrations.max-body-bytes}, 429 — превышена частота, 400 — тело не разобрано
 * (записывается в журнал источника).
 */
@RestController
class InboundEventsController {

    static final String PATH = "/api/v1/integrations/events";
    private static final Logger log = LoggerFactory.getLogger(InboundEventsController.class);

    private final SourceStore sources;
    private final AlertProcessor processor;
    private final RateLimiter limiter;
    private final IntegrationsProperties props;
    private final JsonMapper json;

    InboundEventsController(SourceStore sources, AlertProcessor processor, RateLimiter limiter,
                            IntegrationsProperties props, JsonMapper json) {
        this.sources = sources;
        this.processor = processor;
        this.limiter = limiter;
        this.props = props;
        this.json = json;
    }

    /** Роль не проверяется: вызывающий — система мониторинга, её право — токен источника. */
    @PostMapping(PATH)
    @PreAuthorize("permitAll()")
    ResponseEntity<Map<String, Object>> receive(HttpServletRequest request) throws Exception {
        String token = bearer(request.getHeader(HttpHeaders.AUTHORIZATION))
                .orElseThrow(InboundEventsController::unauthorized);
        SourceStore.TokenMatch match = sources.byToken(token).orElseThrow(() -> {
            log.warn("Событие мониторинга с неизвестным токеном с адреса {}", request.getRemoteAddr());
            return unauthorized();
        });
        if (!limiter.tryAcquire(match.sourceId())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                    "Слишком много событий от источника, повторите позже");
        }
        byte[] body = readLimited(request);
        return TenantContext.callAs(match.tenantId(), () -> handle(match, body));
    }

    private ResponseEntity<Map<String, Object>> handle(SourceStore.TokenMatch match, byte[] body) {
        MonitoringSource source = sources.find(match.sourceId()).orElseThrow(InboundEventsController::unauthorized);
        if (!source.enabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "source_disabled", "Источник выключен в настройках");
        }
        sources.touch(source.id());
        List<Alert> alerts;
        try {
            JsonNode tree = json.readTree(body);
            alerts = AlertFormats.parse(source.kind(), tree);
        } catch (JacksonException e) {
            processor.rejected(source, "тело не является JSON");
            throw AlertFormats.invalid("Тело запроса не является JSON");
        } catch (ApiException e) {
            processor.rejected(source, e.getMessage());
            throw e;
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < alerts.size(); i++) {
            if (i >= props.maxAlertsPerEvent()) {
                skipped = alerts.size() - i;
                processor.rejected(source, "в запросе больше " + props.maxAlertsPerEvent() + " алертов, пропущено "
                        + skipped);
                break;
            }
            AlertProcessor.Outcome o = processor.process(source, alerts.get(i));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("fingerprint", o.fingerprint());
            r.put("outcome", o.outcome());
            if (o.ticket() != null) {
                r.put("ticket", o.ticket());
            }
            if (o.detail() != null) {
                r.put("detail", o.detail());
            }
            results.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", results.size());
        out.put("skipped", skipped);
        out.put("results", results);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
    }

    private byte[] readLimited(HttpServletRequest request) throws IOException {
        int max = props.maxBodyBytes();
        if (request.getContentLengthLong() > max) {
            throw tooLarge(max);
        }
        try (InputStream in = request.getInputStream()) {
            byte[] data = in.readNBytes(max + 1);
            if (data.length > max) {
                throw tooLarge(max);
            }
            return data;
        }
    }

    static Optional<String> bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = header.substring(7).strip();
        return token.isEmpty() || token.length() > 200 ? Optional.empty() : Optional.of(token);
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_token", "Нет или неверный токен источника");
    }

    private static ApiException tooLarge(int max) {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large",
                "Тело запроса больше " + max + " байт");
    }
}
