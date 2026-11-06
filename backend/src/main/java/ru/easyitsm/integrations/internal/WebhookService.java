package ru.easyitsm.integrations.internal;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.admin.api.ConfigHistory;
import ru.easyitsm.integrations.api.Webhooks;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.PageParams;
import tools.jackson.databind.json.JsonMapper;

/**
 * Исходящие вебхуки (раздел 8.3): получатели, разрешённые адреса, очередь и доставка.
 * <p>
 * Тело — {@code {"id", "type", "schema_version": 1, "occurred_at", "tenant", "data"}}, байты сохраняются в очереди,
 * поэтому повтор отправляет то же самое. Подпись — заголовок {@code X-EasyITSM-Signature: sha256=<hex>}, HMAC-SHA256
 * ключом получателя от строки {@code <X-EasyITSM-Timestamp>.<тело>}. Ошибка — повтор через 1, 2, 4 … 128 минут
 * (8 повторов), затем доставка неудачная и повторяется только вручную. Получатель обрабатывает повторы
 * идемпотентно по {@code id}.
 */
@Service
class WebhookService implements Webhooks {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    static final String HISTORY_ENTITY = "integrations.webhook";
    static final String POLICY_ENTITY = "integrations.outbound_policy";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_HOSTS = 100;

    /** Получатель и ключ подписи: ключ показывается только в этом ответе. */
    record WithSecret(WebhookStore.Webhook webhook, String secret) {
    }

    record WebhookInput(String name, String url, Boolean enabled) {
    }

    record PolicyInput(List<String> allowedHosts) {
    }

    /** Итог проверочной отправки. */
    record TestResult(boolean ok, Integer status, String error, String response) {
    }

    private final WebhookStore webhooks;
    private final DeliveryStore deliveries;
    private final OutboundHttp http;
    private final WebhookSecrets secrets;
    private final ConfigHistory history;
    private final IntegrationsProperties props;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    WebhookService(WebhookStore webhooks, DeliveryStore deliveries, OutboundHttp http, WebhookSecrets secrets,
                   ConfigHistory history, IntegrationsProperties props, JsonMapper json,
                   PlatformTransactionManager txManager) {
        this.webhooks = webhooks;
        this.deliveries = deliveries;
        this.http = http;
        this.secrets = secrets;
        this.history = history;
        this.props = props;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---------------------------------------------------------------- для других модулей

    @Override
    @Transactional(readOnly = true)
    public List<Ref> list() {
        return webhooks.list().stream().map(w -> new Ref(w.id(), w.name(), w.enabled())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Ref> find(UUID webhookId) {
        return webhookId == null ? Optional.empty()
                : webhooks.find(webhookId).map(w -> new Ref(w.id(), w.name(), w.enabled()));
    }

    @Override
    @Transactional
    public UUID enqueue(UUID webhookId, String eventType, Map<String, Object> data, String source,
                        String ticketNumber) {
        WebhookStore.Webhook w = webhooks.find(webhookId).filter(WebhookStore.Webhook::enabled)
                .orElseThrow(() -> ApiException.badRequest("webhook_unavailable", "Вебхук выключен или удалён"));
        UUID id = Ids.newId();
        deliveries.insert(id, w.id(), eventType, body(id, eventType, data), source, ticketNumber);
        return id;
    }

    private String body(UUID id, String type, Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id.toString());
        m.put("type", type);
        m.put("schema_version", 1);
        m.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        m.put("tenant", TenantContext.require().toString());
        m.put("data", data == null ? Map.of() : data);
        return json.writeValueAsString(m);
    }

    // ---------------------------------------------------------------- настройки

    @Transactional(readOnly = true)
    List<WebhookStore.Webhook> webhooks() {
        return webhooks.list();
    }

    @Transactional(readOnly = true)
    WebhookStore.Webhook get(UUID id) {
        return require(id);
    }

    @Transactional
    WithSecret create(WebhookInput in) {
        String name = name(in.name());
        String url = url(in.url());
        String secret = newSecret();
        UUID id = webhooks.insert(name, url, !Boolean.FALSE.equals(in.enabled()), secrets.encrypt(secret));
        WebhookStore.Webhook w = require(id);
        record(w, "create", null);
        return new WithSecret(w, secret);
    }

    @Transactional
    WebhookStore.Webhook update(UUID id, String ifMatch, WebhookInput in) {
        WebhookStore.Webhook current = require(id);
        ETags.requireMatch(ifMatch, current.version());
        String name = in.name() == null ? current.name() : name(in.name());
        String url = in.url() == null ? current.url() : url(in.url());
        boolean enabled = in.enabled() == null ? current.enabled() : in.enabled();
        if (!webhooks.update(id, current.version(), name, url, enabled)) {
            throw ApiException.conflict("version_conflict", "Вебхук изменён другим пользователем. Обновите и повторите");
        }
        WebhookStore.Webhook updated = require(id);
        record(updated, "update", null);
        return updated;
    }

    @Transactional
    WithSecret rotate(UUID id) {
        require(id);
        String secret = newSecret();
        webhooks.rotateSecret(id, secrets.encrypt(secret));
        WebhookStore.Webhook w = require(id);
        record(w, "update", "Перевыпуск ключа подписи");
        return new WithSecret(w, secret);
    }

    @Transactional
    void delete(UUID id) {
        WebhookStore.Webhook w = require(id);
        webhooks.delete(id);
        record(w, "delete", null);
    }

    @Transactional(readOnly = true)
    WebhookStore.Policy policy() {
        return webhooks.policy();
    }

    @Transactional
    WebhookStore.Policy savePolicy(String ifMatch, PolicyInput in) {
        WebhookStore.Policy current = webhooks.policy();
        ETags.requireMatch(ifMatch, current.version());
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        for (String h : in.allowedHosts() == null ? List.<String>of() : in.allowedHosts()) {
            if (h == null || h.isBlank()) {
                continue;
            }
            String rule = h.strip().toLowerCase(java.util.Locale.ROOT);
            if (!OutboundGuard.validRule(rule)) {
                throw ApiException.badRequest("invalid_policy", "«" + h.strip() + "» — не имя узла, *.домен или сеть CIDR");
            }
            hosts.add(rule);
        }
        if (hosts.size() > MAX_HOSTS) {
            throw ApiException.badRequest("invalid_policy", "Не больше " + MAX_HOSTS + " адресов");
        }
        if (!webhooks.savePolicy(new ArrayList<>(hosts), current.version())) {
            throw ApiException.conflict("version_conflict", "Список изменён другим пользователем. Обновите и повторите");
        }
        WebhookStore.Policy saved = webhooks.policy();
        history.record(POLICY_ENTITY, "", "Разрешённые адреса вебхуков", "update", Map.of("allowedHosts", saved.allowedHosts()),
                null);
        return saved;
    }

    @Transactional(readOnly = true)
    CursorPage<DeliveryStore.Delivery> deliveries(UUID webhookId, String status, PageParams page) {
        return deliveries.page(webhookId, status, page);
    }

    @Transactional(readOnly = true)
    Map<String, Object> delivery(UUID id) {
        DeliveryStore.Delivery d = deliveries.find(id).orElseThrow(() -> ApiException.notFound("Доставка"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("delivery", d);
        m.put("payload", deliveries.payload(id).orElse(null));
        return m;
    }

    @Transactional
    DeliveryStore.Delivery retry(UUID id) {
        deliveries.find(id).orElseThrow(() -> ApiException.notFound("Доставка"));
        if (!deliveries.retry(id)) {
            throw ApiException.conflict("not_failed", "Повторить можно только неудачную доставку");
        }
        return deliveries.find(id).orElseThrow();
    }

    /** «Тест вебхука»: событие {@code webhook.test} отправляется сразу, без очереди и повторов. */
    TestResult test(UUID id) {
        WebhookStore.Webhook w = tx.execute(s -> require(id));
        String secret = tx.execute(s -> webhooks.secret(id).map(secrets::decryptOrNull).orElse(null));
        if (secret == null) {
            return new TestResult(false, null, "ключ подписи не расшифровывается: перевыпустите его", null);
        }
        List<String> allowed = tx.execute(s -> webhooks.policy().allowedHosts());
        UUID eventId = Ids.newId();
        String body = body(eventId, "webhook.test", Map.of("message", "Проверка вебхука «" + w.name() + "»"));
        OutboundHttp.Result r = send(allowed, w.url(), secret, eventId, "webhook.test", body);
        return new TestResult(r.ok(), r.status() == 0 ? null : r.status(), r.error(), r.responseSnippet());
    }

    // ---------------------------------------------------------------- доставка

    /** Одна доставка в арендаторе вызывающего. @return true — отправлялась (успешно или нет) */
    boolean deliver(UUID deliveryId) {
        Boolean sent = tx.execute(s -> {
            Optional<DeliveryStore.Due> due = deliveries.lock(deliveryId);
            if (due.isEmpty()) {
                return false;
            }
            DeliveryStore.Due d = due.get();
            int attempt = d.attempts() + 1;
            Optional<WebhookStore.Webhook> hook = webhooks.find(d.webhookId());
            if (hook.isEmpty() || !hook.get().enabled()) {
                deliveries.failed(d.id(), attempt, null, "получатель выключен", null);
                return true;
            }
            String secret = webhooks.secret(d.webhookId()).map(secrets::decryptOrNull).orElse(null);
            if (secret == null) {
                deliveries.failed(d.id(), attempt, null, "ключ подписи не расшифровывается: перевыпустите его", null);
                return true;
            }
            OutboundHttp.Result r = send(webhooks.policy().allowedHosts(), hook.get().url(), secret, d.id(),
                    d.eventType(), d.payload());
            if (r.ok()) {
                deliveries.delivered(d.id(), attempt, r.status());
                webhooks.touch(d.webhookId());
            } else {
                Instant next = attempt >= props.outbound().maxAttempts() ? null
                        : Instant.now().plus(backoff(attempt));
                deliveries.failed(d.id(), attempt, r.status() == 0 ? null : r.status(), r.error(), next);
                if (next == null) {
                    log.warn("Вебхук {}: доставка {} неудачна после {} попыток: {}", d.webhookId(), d.id(), attempt,
                            r.error());
                }
            }
            return true;
        });
        return Boolean.TRUE.equals(sent);
    }

    /** Пауза перед повтором после попытки {@code attempt}: 1, 2, 4 … минуты. */
    static Duration backoff(int attempt) {
        return Duration.ofMinutes(1L << Math.min(Math.max(attempt - 1, 0), 10));
    }

    private OutboundHttp.Result send(List<String> allowed, String url, String secret, UUID eventId, String type,
                                     String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String ts = Long.toString(Instant.now().getEpochSecond());
        byte[] signed = (ts + ".").getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[signed.length + bytes.length];
        System.arraycopy(signed, 0, data, 0, signed.length);
        System.arraycopy(bytes, 0, data, signed.length, bytes.length);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "EasyITSM-Webhook/1");
        headers.put("X-EasyITSM-Event", type);
        headers.put("X-EasyITSM-Delivery", eventId.toString());
        headers.put("X-EasyITSM-Timestamp", ts);
        headers.put("X-EasyITSM-Signature", "sha256=" + HexFormat.of().formatHex(WebhookSecrets.hmac(secret, data)));
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return new OutboundHttp.Result(0, "неверный адрес", null);
        }
        return http.post(http.guard(allowed), uri, headers, bytes);
    }

    // ---------------------------------------------------------------- проверки

    private WebhookStore.Webhook require(UUID id) {
        return webhooks.find(id).orElseThrow(() -> ApiException.notFound("Вебхук"));
    }

    private void record(WebhookStore.Webhook w, String action, String comment) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("name", w.name());
        snap.put("url", w.url());
        snap.put("enabled", w.enabled());
        history.record(HISTORY_ENTITY, w.id().toString(), w.name(), action, snap, comment);
    }

    private static String name(String name) {
        if (name == null || name.isBlank()) {
            throw invalid("Укажите название вебхука");
        }
        if (name.strip().length() > 200) {
            throw invalid("Название — не длиннее 200 символов");
        }
        return name.strip();
    }

    /** Адрес проверяется по форме; разрешён ли узел — при каждой отправке. */
    private static String url(String url) {
        if (url == null || url.isBlank()) {
            throw invalid("Укажите адрес получателя");
        }
        String u = url.strip();
        try {
            URI uri = new URI(u);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null) {
                throw invalid("Адрес должен начинаться с https:// или http:// и содержать узел");
            }
            if (uri.getRawUserInfo() != null) {
                throw invalid("Логин и пароль в адресе не допускаются");
            }
        } catch (java.net.URISyntaxException e) {
            throw invalid("Неверный адрес");
        }
        if (u.length() > 2000) {
            throw invalid("Адрес — не длиннее 2000 символов");
        }
        return u;
    }

    private static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static ApiException invalid(String detail) {
        return ApiException.badRequest("invalid_webhook", detail);
    }
}
