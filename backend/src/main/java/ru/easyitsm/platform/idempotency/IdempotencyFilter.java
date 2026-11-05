package ru.easyitsm.platform.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.TraceIdFilter;

/**
 * Идемпотентное создание (API-04): POST с заголовком {@code Idempotency-Key} в течение 24 часов
 * возвращает сохранённый результат первого запроса и не выполняет его повторно.
 * <p>
 * Ключ уникален в пределах арендатора и пользователя. Тот же ключ с другим телом — 422,
 * повтор, пока первый запрос ещё выполняется, — 409. Ответы 5xx не сохраняются, чтобы повтор был возможен.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static final UUID ANONYMOUS = new UUID(0, 0);

    private final JdbcClient jdbc;

    public IdempotencyFilter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || request.getHeader(HEADER) == null
                || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER).trim();
        if (key.length() < 8 || key.length() > 128) {
            problem(response, HttpStatus.BAD_REQUEST, "invalid_idempotency_key", "Idempotency-Key: от 8 до 128 символов");
            return;
        }
        Optional<UUID> tenant = TenantContext.current();
        if (tenant.isEmpty()) {
            chain.doFilter(request, response); // без арендатора запрос всё равно будет отклонён дальше
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            problem(response, HttpStatus.CONTENT_TOO_LARGE, "body_too_large", "Слишком большой запрос");
            return;
        }
        UUID actor = CurrentActor.userId().orElse(ANONYMOUS);
        String hash = hash(request.getMethod(), request.getRequestURI(), body);

        if (!claim(tenant.get(), actor, key, hash)) {
            replayOrReject(tenant.get(), actor, key, hash, response);
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(new CachedBodyRequest(request, body), wrapped);
            if (wrapped.getStatus() < 500) {
                jdbc.sql("""
                        update idempotency_record
                           set status_code = :status, content_type = :ct, response_body = :body
                         where tenant_id = :tenant and actor_id = :actor and idem_key = :key""")
                        .param("status", wrapped.getStatus())
                        .param("ct", wrapped.getContentType())
                        .param("body", wrapped.getContentAsByteArray())
                        .param("tenant", tenant.get()).param("actor", actor).param("key", key)
                        .update();
                stored = true;
            }
        } finally {
            if (!stored) {
                jdbc.sql("delete from idempotency_record where tenant_id = :tenant and actor_id = :actor and idem_key = :key")
                        .param("tenant", tenant.get()).param("actor", actor).param("key", key)
                        .update();
            }
            wrapped.copyBodyToResponse();
        }
    }

    /** Занимает ключ. true — запрос выполняется впервые (или прошлый результат старше 24 часов). */
    private boolean claim(UUID tenant, UUID actor, String key, String hash) {
        int inserted = jdbc.sql("""
                insert into idempotency_record (tenant_id, actor_id, idem_key, request_hash)
                values (:tenant, :actor, :key, :hash)
                on conflict do nothing""")
                .param("tenant", tenant).param("actor", actor).param("key", key).param("hash", hash)
                .update();
        if (inserted == 1) {
            return true;
        }
        int reclaimed = jdbc.sql("""
                update idempotency_record
                   set request_hash = :hash, status_code = null, content_type = null, response_body = null, created_at = now()
                 where tenant_id = :tenant and actor_id = :actor and idem_key = :key
                   and created_at < now() - interval '24 hours'""")
                .param("tenant", tenant).param("actor", actor).param("key", key).param("hash", hash)
                .update();
        return reclaimed == 1;
    }

    private void replayOrReject(UUID tenant, UUID actor, String key, String hash, HttpServletResponse response)
            throws IOException {
        var stored = jdbc.sql("""
                select request_hash, status_code, content_type, response_body
                  from idempotency_record
                 where tenant_id = :tenant and actor_id = :actor and idem_key = :key""")
                .param("tenant", tenant).param("actor", actor).param("key", key)
                .query((rs, n) -> new Stored(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3), rs.getBytes(4)))
                .optional();
        if (stored.isEmpty()) {
            problem(response, HttpStatus.CONFLICT, "request_in_progress", "Запрос с этим ключом ещё выполняется");
            return;
        }
        Stored s = stored.get();
        if (!s.hash().equals(hash)) {
            problem(response, HttpStatus.UNPROCESSABLE_CONTENT, "idempotency_key_reused",
                    "Ключ уже использован для другого запроса");
            return;
        }
        if (s.status() == null) {
            problem(response, HttpStatus.CONFLICT, "request_in_progress", "Запрос с этим ключом ещё выполняется");
            return;
        }
        response.setStatus(s.status());
        if (s.contentType() != null) {
            response.setContentType(s.contentType());
        }
        response.setHeader("Idempotency-Replayed", "true");
        if (s.body() != null) {
            response.getOutputStream().write(s.body());
        }
    }

    private static void problem(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"https://easyitsm.ru/problems/" + code + "\",\"title\":\""
                + status.getReasonPhrase() + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail
                + "\",\"code\":\"" + code + "\",\"trace_id\":\"" + TraceIdFilter.current() + "\"}");
    }

    private static String hash(String method, String path, byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((method + " " + path + "\n").getBytes(StandardCharsets.UTF_8));
            md.update(body);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Stored(String hash, Integer status, String contentType, byte[] body) {
    }
}
