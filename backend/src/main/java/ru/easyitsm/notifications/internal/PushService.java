package ru.easyitsm.notifications.internal;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Web Push (NTF-01): ключи VAPID арендатора, подписки браузеров и отправка. Адрес подписки должен быть https
 * и вести на известный push-сервис браузера — сервер не отправит запрос на произвольный адрес (SSRF).
 */
@Service
class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    /** Push-сервисы браузеров: Chrome/Edge (FCM), Firefox, Safari, Windows. */
    static final List<String> PUSH_HOSTS = List.of("fcm.googleapis.com", "updates.push.services.mozilla.com",
            ".push.apple.com", ".notify.windows.com", "android.googleapis.com");

    private final JdbcClient jdbc;
    private final KeyBox keys;
    private final NotificationProperties props;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault()).followRedirects(HttpClient.Redirect.NEVER).build();

    PushService(JdbcClient jdbc, KeyBox keys, NotificationProperties props) {
        this.jdbc = jdbc;
        this.keys = keys;
        this.props = props;
    }

    record Vapid(byte[] publicRaw, PrivateKey privateKey) {
    }

    /** Ключ VAPID арендатора; создаётся при первом обращении. */
    @Transactional
    public Vapid vapid() {
        var row = jdbc.sql("select public_key, private_key_enc from push_vapid_key")
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).optional();
        try {
            if (row.isPresent()) {
                try {
                    PrivateKey pk = KeyFactory.getInstance("EC")
                            .generatePrivate(new PKCS8EncodedKeySpec(keys.decrypt(row.get()[1])));
                    return new Vapid(WebPush.unb64(row.get()[0]), pk);
                } catch (GeneralSecurityException e) {
                    // Сменился секретный ключ установки: старый ключ VAPID не расшифровать. Создаём новый,
                    // прежние подписки браузеров перестанут приниматься push-сервисами и подпишутся заново.
                    log.warn("Ключ VAPID не расшифровывается, создаётся новый; подписки Web Push сброшены");
                    jdbc.sql("delete from push_vapid_key").update();
                    jdbc.sql("delete from push_subscription").update();
                }
            }
            KeyPair kp = WebPush.newKeyPair();
            byte[] pub = WebPush.raw((ECPublicKey) kp.getPublic());
            Timestamp now = Timestamp.from(Instant.now());
            try {
                jdbc.sql("""
                        insert into push_vapid_key (id, tenant_id, public_key, private_key_enc, created_at, updated_at, version)
                        values (:id, :tenant, :pub, :priv, :now, :now, 0)""")
                        .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("pub", WebPush.b64(pub))
                        .param("priv", keys.encrypt(kp.getPrivate().getEncoded())).param("now", now).update();
            } catch (DuplicateKeyException e) {
                return vapid();
            }
            return new Vapid(pub, kp.getPrivate());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ключ VAPID недоступен: сменился секретный ключ?", e);
        }
    }

    @Transactional
    public void subscribe(UUID userId, String endpoint, String p256dh, String auth, String userAgent) {
        if (!allowedEndpoint(endpoint)) {
            throw ApiException.badRequest("invalid_endpoint", "Адрес подписки не относится к push-сервису браузера");
        }
        try {
            if (WebPush.unb64(auth).length != 16) {
                throw new GeneralSecurityException("auth");
            }
            WebPush.fromRaw(WebPush.unb64(p256dh));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_keys", "Неверные ключи подписки");
        }
        Timestamp now = Timestamp.from(Instant.now());
        // Тот же браузер мог быть подписан другим пользователем: подписка переходит к текущему.
        jdbc.sql("""
                insert into push_subscription (id, tenant_id, user_id, endpoint, p256dh, auth, user_agent,
                    created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :user, :endpoint, :p256dh, :auth, :ua, :now, :user, :now, :user, 0)
                on conflict (tenant_id, endpoint) do update set user_id = excluded.user_id, p256dh = excluded.p256dh,
                    auth = excluded.auth, user_agent = excluded.user_agent, updated_at = excluded.updated_at,
                    updated_by = excluded.updated_by, version = push_subscription.version + 1""")
                .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("user", userId)
                .param("endpoint", endpoint).param("p256dh", p256dh).param("auth", auth)
                .param("ua", userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length())))
                .param("now", now).update();
    }

    @Transactional
    public void unsubscribe(UUID userId, String endpoint) {
        jdbc.sql("delete from push_subscription where user_id = :user and endpoint = :endpoint")
                .param("user", userId).param("endpoint", endpoint).update();
    }

    @Transactional(readOnly = true)
    public long subscriptions(UUID userId) {
        return jdbc.sql("select count(*) from push_subscription where user_id = :u").param("u", userId)
                .query(Long.class).single();
    }

    static boolean allowedEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                return false;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return PUSH_HOSTS.stream().anyMatch(h -> h.startsWith(".") ? host.endsWith(h) : host.equals(h));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Отправляет push по записи центра уведомлений на все подписки получателя. */
    @Transactional
    public void deliver(UUID notificationId) {
        var n = jdbc.sql("""
                select user_id, title, body, link from notification
                 where id = :id and push_state = 'pending' for update skip locked""")
                .param("id", notificationId)
                .query((rs, i) -> new String[] {rs.getString("user_id"), rs.getString("title"), rs.getString("body"),
                        rs.getString("link")})
                .optional();
        if (n.isEmpty()) {
            return;
        }
        UUID userId = UUID.fromString(n.get()[0]);
        record Sub(UUID id, String endpoint, String p256dh, String auth) {
        }
        List<Sub> subs = jdbc.sql("select id, endpoint, p256dh, auth from push_subscription where user_id = :u")
                .param("u", userId)
                .query((rs, i) -> new Sub(rs.getObject("id", UUID.class), rs.getString("endpoint"),
                        rs.getString("p256dh"), rs.getString("auth")))
                .list();
        boolean ok = subs.isEmpty();
        if (!subs.isEmpty() && props.pushEnabled()) {
            Vapid v = vapid();
            byte[] payload = json.writeValueAsBytes(Map.of("id", notificationId.toString(), "title", n.get()[1],
                    "body", n.get()[2], "link", n.get()[3] == null ? "/" : n.get()[3]));
            for (Sub s : subs) {
                try {
                    int status = post(s.endpoint(), WebPush.encrypt(payload, WebPush.unb64(s.p256dh()),
                            WebPush.unb64(s.auth())), v);
                    if (status == 404 || status == 410) {
                        jdbc.sql("delete from push_subscription where id = :id").param("id", s.id()).update();
                    } else if (status / 100 == 2) {
                        ok = true;
                    } else {
                        log.info("Push-сервис ответил {} для подписки {}", status, s.id());
                    }
                } catch (Exception e) {
                    log.info("Push не отправлен на подписку {}: {}", s.id(), e.toString());
                }
            }
        }
        jdbc.sql("update notification set push_state = :state, updated_at = now() where id = :id")
                .param("id", notificationId).param("state", ok ? "sent" : "failed").update();
    }

    private int post(String endpoint, byte[] body, Vapid v) throws Exception {
        if (!allowedEndpoint(endpoint)) {
            return 410;
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", "86400")
                .header("Urgency", "normal")
                .header("Authorization", WebPush.vapidAuthorization(endpoint, props.pushSubject(), v.privateKey(),
                        v.publicRaw()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
