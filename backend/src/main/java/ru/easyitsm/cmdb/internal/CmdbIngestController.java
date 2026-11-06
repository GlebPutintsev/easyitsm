package ru.easyitsm.cmdb.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.security.SecurityChainCustomizer;
import ru.easyitsm.platform.tenant.TenantContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Приём пакетов от агента обнаружения и коннекторов по токену источника (CFG-04): {@code Authorization: Bearer
 * eitsm_cmdb_…}, без сессии и CSRF. Токен действует только для своего источника, арендатор определяется по
 * токену. Тело и ответ — как у {@code POST /api/v1/cmdb/sources/{code}/batches}.
 * <p>
 * Токен проверяется до чтения тела: без него сервер не разбирает пакет. 401 — нет или неверный токен,
 * 409 {@code source_inactive} — источник выключен, 413 — пакет больше 5 МБ, 400 — тело не разобрано.
 */
@RestController
class CmdbIngestController {

    static final String BATCHES = "/api/v1/cmdb/ingest/batches";
    static final String SOURCE = "/api/v1/cmdb/ingest/source";
    private static final Logger log = LoggerFactory.getLogger(CmdbIngestController.class);

    /** Что агент узнаёт о своём источнике при проверке настройки ({@code discovery-agent check}). */
    record IngestSource(String code, String name, String kind, int priority, boolean active) {
    }

    private final SourceService sources;
    private final IngestService ingest;
    private final JsonMapper json;
    private final Validator validator;

    CmdbIngestController(SourceService sources, IngestService ingest, JsonMapper json, Validator validator) {
        this.sources = sources;
        this.ingest = ingest;
        this.json = json;
        this.validator = validator;
    }

    /** Роль не проверяется: вызывающий — агент, его право — токен источника. */
    @PostMapping(BATCHES)
    @PreAuthorize("permitAll()")
    IngestService.BatchResult ingest(HttpServletRequest request) throws Exception {
        SourceService.TokenMatch match = authenticate(request);
        IngestService.BatchRequest batch = parse(readLimited(request));
        return TenantContext.callAs(match.tenantId(), () -> ingest.ingest(match.code(), batch));
    }

    @GetMapping(SOURCE)
    @PreAuthorize("permitAll()")
    IngestSource source(HttpServletRequest request) throws Exception {
        SourceService.TokenMatch match = authenticate(request);
        return TenantContext.callAs(match.tenantId(), () -> {
            SourceService.Source s = sources.get(match.code());
            return new IngestSource(s.code(), s.name(), s.kind(), s.priority(), s.active());
        });
    }

    private SourceService.TokenMatch authenticate(HttpServletRequest request) {
        String token = SourceTokens.bearer(request.getHeader(HttpHeaders.AUTHORIZATION))
                .orElseThrow(CmdbIngestController::unauthorized);
        return sources.byToken(token).orElseThrow(() -> {
            log.warn("Пакет CMDB с неизвестным токеном с адреса {}", request.getRemoteAddr());
            return unauthorized();
        });
    }

    private static byte[] readLimited(HttpServletRequest request) throws IOException {
        long max = IngestService.MAX_BYTES;
        if (request.getContentLengthLong() > max) {
            throw tooLarge();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] data = in.readNBytes((int) max + 1);
            if (data.length > max) {
                throw tooLarge();
            }
            return data;
        }
    }

    private IngestService.BatchRequest parse(byte[] body) {
        IngestService.BatchRequest batch;
        try {
            batch = json.readValue(body, IngestService.BatchRequest.class);
        } catch (JacksonException e) {
            throw CmdbProblem.invalid("invalid_batch", "body", "Тело запроса — не пакет CMDB в JSON");
        }
        if (batch == null) {
            throw CmdbProblem.invalid("invalid_batch", "body", "Пустое тело запроса");
        }
        Set<ConstraintViolation<IngestService.BatchRequest>> violations = validator.validate(batch);
        violations.stream().min(Comparator.comparing(v -> v.getPropertyPath().toString())).ifPresent(v -> {
            throw CmdbProblem.invalid("invalid_batch", v.getPropertyPath().toString(),
                    "Поле «" + v.getPropertyPath() + "»: " + v.getMessage());
        });
        return batch;
    }

    private static CmdbProblem unauthorized() {
        return new CmdbProblem(HttpStatus.UNAUTHORIZED, "invalid_token", "Нет или неверный токен источника");
    }

    private static CmdbProblem tooLarge() {
        return new CmdbProblem(HttpStatus.CONTENT_TOO_LARGE, "batch_too_large",
                "Пакет больше 5 МБ: разбейте его на несколько");
    }

    /** Пути приёма открыты в цепочке безопасности: право проверяет контроллер по токену. */
    @Configuration(proxyBeanMethods = false)
    static class Security {

        @Bean
        SecurityChainCustomizer cmdbIngestSecurityCustomizer() {
            return http -> http.authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.POST, BATCHES).permitAll()
                    .requestMatchers(HttpMethod.GET, SOURCE).permitAll());
        }
    }
}
