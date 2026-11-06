package ru.easyitsm.integrations.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Приём сообщений бота Teams от Bot Framework ({@code POST /api/v1/integrations/teams/{id}/messages}): адрес
 * указывается как Messaging endpoint бота в Azure. Вход — только JWT Bot Framework, проверяемый {@link TeamsAuth}:
 * без сессии и CSRF, арендатор определяется по id бота. 401 — нет или неверный токен (и неизвестный бот, чтобы не
 * раскрывать, какие боты есть), 413 — тело больше 256 КБ, 429 — превышена частота.
 */
@RestController
class TeamsController {

    static final String PATH = "/api/v1/integrations/teams/{id}/messages";
    static final int MAX_BODY = 256 * 1024;
    private static final Logger log = LoggerFactory.getLogger(TeamsController.class);

    private final MessengerStore store;
    private final MessengerRunner runner;
    private final TeamsAuth auth;
    private final RateLimiter limiter;
    private final JsonMapper json;
    private final OutboundHttp http;
    private final TransactionTemplate tx;

    TeamsController(MessengerStore store, MessengerRunner runner, TeamsAuth auth, RateLimiter limiter, JsonMapper json,
                    OutboundHttp http, PlatformTransactionManager txManager) {
        this.store = store;
        this.runner = runner;
        this.auth = auth;
        this.limiter = limiter;
        this.json = json;
        this.http = http;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Роль не проверяется: вызывающий — Bot Framework, его право — подписанный JWT. */
    @PostMapping(PATH)
    @PreAuthorize("permitAll()")
    ResponseEntity<Map<String, Object>> receive(@PathVariable UUID id, HttpServletRequest request) throws Exception {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7) || header.length() > 8192) {
            throw unauthorized();
        }
        String token = header.substring(7).strip();
        MessengerStore.Streaming bot = store.teamsBot(id).orElseThrow(TeamsController::unauthorized);
        if (!limiter.tryAcquire(id)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Слишком много сообщений, повторите позже");
        }
        byte[] body = read(request);
        return TenantContext.callAs(bot.tenantId(), () -> handle(id, token, body, request.getRemoteAddr()));
    }

    private ResponseEntity<Map<String, Object>> handle(UUID id, String token, byte[] body, String remote) {
        MessengerStore.Messenger m = tx.execute(s -> store.find(id)).orElseThrow(TeamsController::unauthorized);
        Jwt jwt;
        try {
            jwt = auth.verify(token, m.appId());
        } catch (JwtException e) {
            log.warn("Teams {}: запрос с неверным токеном с адреса {}: {}", id, remote, e.getMessage());
            throw unauthorized();
        }
        JsonNode activity;
        try {
            activity = json.readTree(body);
        } catch (JacksonException e) {
            throw ApiException.badRequest("invalid_activity", "Тело запроса — не JSON");
        }
        String serviceUrl = activity.path("serviceUrl").asString(null);
        String claimed = jwt.getClaimAsString("serviceUrl");
        if (serviceUrl == null || claimed != null && !same(claimed, serviceUrl)
                || TeamsConnector.serviceUri(serviceUrl, http.loopbackAllowed()) == null) {
            log.warn("Teams {}: недопустимый serviceUrl {}", id, serviceUrl);
            throw ApiException.badRequest("invalid_activity", "Недопустимый адрес Bot Framework");
        }
        MessengerConnector.Update u = TeamsConnector.parse(activity);
        if (u == null) {
            return ResponseEntity.ok(Map.of());
        }
        tx.executeWithoutResult(s -> store.conversation(id, u.chatId(), serviceUrl));
        runner.handle(id, u);
        return ResponseEntity.ok(Map.of());
    }

    private static boolean same(String a, String b) {
        return a.replaceAll("/+$", "").equalsIgnoreCase(b.replaceAll("/+$", ""));
    }

    private static byte[] read(HttpServletRequest request) throws java.io.IOException {
        if (request.getContentLengthLong() > MAX_BODY) {
            throw tooLarge();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] data = in.readNBytes(MAX_BODY + 1);
            if (data.length > MAX_BODY) {
                throw tooLarge();
            }
            return data;
        }
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_token", "Нет или неверный токен Bot Framework");
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large", "Тело запроса больше 256 КБ");
    }
}
