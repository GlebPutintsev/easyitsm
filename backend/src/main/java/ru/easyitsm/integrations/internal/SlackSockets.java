package ru.easyitsm.integrations.internal;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.tenant.TenantContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Socket Mode Slack на worker: для каждого включённого бота Slack держит WebSocket, который открывает сам сервер.
 * Slack раздаёт события между соединениями приложения (до 10), поэтому каждый worker держит своё. Конверт
 * подтверждается сразу, затем сообщение обрабатывается в своей транзакции; повторную доставку отсекает
 * {@link MessengerBot}. Соединение пересоздаётся, если Slack его закрыл, бот изменён или выключен.
 */
@Component
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
class SlackSockets {

    private static final Logger log = LoggerFactory.getLogger(SlackSockets.class);
    private static final Duration FIRST_RETRY = Duration.ofSeconds(30);
    private static final Duration MAX_RETRY = Duration.ofMinutes(10);
    /** Больше одного мегабайта в одном сообщении Slack не присылает. */
    private static final int MAX_FRAME = 1 << 20;

    private final MessengerStore store;
    private final MessengerRunner runner;
    private final JsonMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService handlers = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "slack-events");
        t.setDaemon(true);
        return t;
    });
    private final Map<UUID, Conn> conns = new ConcurrentHashMap<>();
    private final Map<UUID, Retry> retries = new ConcurrentHashMap<>();

    private record Retry(Instant at, Duration delay) {
    }

    SlackSockets(MessengerStore store, MessengerRunner runner, JsonMapper json) {
        this.store = store;
        this.runner = runner;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${easyitsm.integrations.slack-interval:PT10S}",
            initialDelayString = "${easyitsm.integrations.messenger-initial-delay:PT20S}")
    void supervise() {
        Map<UUID, MessengerStore.Streaming> wanted = new HashMap<>();
        for (MessengerStore.Streaming s : store.streamingEverywhere()) {
            wanted.put(s.id(), s);
        }
        conns.entrySet().removeIf(e -> {
            MessengerStore.Streaming w = wanted.get(e.getKey());
            Conn c = e.getValue();
            boolean keep = w != null && w.version() == c.version && !c.closed;
            if (!keep) {
                c.close();
            }
            return !keep;
        });
        retries.keySet().removeIf(id -> !wanted.containsKey(id));
        for (MessengerStore.Streaming w : wanted.values()) {
            Retry r = retries.get(w.id());
            if (conns.containsKey(w.id()) || r != null && Instant.now().isBefore(r.at())) {
                continue;
            }
            connect(w);
        }
    }

    private void connect(MessengerStore.Streaming w) {
        Conn c = new Conn(w.tenantId(), w.id(), w.version());
        try {
            URI uri = TenantContext.callAs(w.tenantId(), () -> runner.socketUrl(w.id()));
            if (uri == null) {
                return;
            }
            c.ws = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, c)
                    .get(15, TimeUnit.SECONDS);
            conns.put(w.id(), c);
        } catch (Exception e) {
            String error = e instanceof MessengerConnector.Failure || e instanceof OutboundGuard.Denied ? e.getMessage()
                    : "не удалось открыть соединение Socket Mode";
            failed(c, error);
            if (!(e instanceof MessengerConnector.Failure || e instanceof OutboundGuard.Denied)) {
                log.warn("Slack {}: соединение не открыто", w.id(), e);
            }
        }
    }

    private void failed(Conn c, String error) {
        Retry prev = retries.get(c.id);
        Duration delay = prev == null ? FIRST_RETRY : prev.delay().multipliedBy(2);
        if (delay.compareTo(MAX_RETRY) > 0) {
            delay = MAX_RETRY;
        }
        retries.put(c.id, new Retry(Instant.now().plus(delay), delay));
        status(c, error);
    }

    private void status(Conn c, String error) {
        try {
            TenantContext.runAs(c.tenantId, () -> runner.status(c.id, error));
        } catch (RuntimeException e) {
            log.warn("Slack {}: состояние не записано", c.id, e);
        }
    }

    /** Кадр Socket Mode: hello, disconnect или конверт события, который подтверждается сразу. */
    void onMessage(Conn c, String text) {
        JsonNode node;
        try {
            node = json.readTree(text);
        } catch (JacksonException e) {
            return;
        }
        switch (node.path("type").asString("")) {
            case "hello" -> {
                c.ready = true;
                retries.remove(c.id);
                status(c, null);
            }
            // Slack заранее предупреждает о закрытии: новое соединение откроется при следующей проверке.
            case "disconnect" -> c.close();
            default -> {
                String envelope = node.path("envelope_id").asString(null);
                if (envelope != null && c.ws != null) {
                    c.ws.sendText(json.writeValueAsString(Map.of("envelope_id", envelope)), true);
                }
                if (!"events_api".equals(node.path("type").asString(""))) {
                    return;
                }
                MessengerConnector.Update u = SlackConnector.parse(node);
                if (u != null) {
                    handlers.execute(() -> {
                        try {
                            TenantContext.runAs(c.tenantId, () -> runner.handle(c.id, u));
                        } catch (RuntimeException e) {
                            log.warn("Slack {}: сообщение не обработано", c.id, e);
                        }
                    });
                }
            }
        }
    }

    /** Закрыть все соединения (остановка приложения, тесты). */
    void closeAll() {
        conns.values().forEach(Conn::close);
        conns.clear();
        retries.clear();
    }

    @PreDestroy
    void stop() {
        closeAll();
        handlers.shutdown();
    }

    /** Одно соединение Socket Mode. */
    final class Conn implements WebSocket.Listener {

        final UUID tenantId;
        final UUID id;
        final long version;
        volatile WebSocket ws;
        volatile boolean closed;
        volatile boolean ready;
        private final StringBuilder buf = new StringBuilder();

        Conn(UUID tenantId, UUID id, long version) {
            this.tenantId = tenantId;
            this.id = id;
            this.version = version;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (buf.length() + data.length() > MAX_FRAME) {
                buf.setLength(0);
                close();
                return null;
            }
            buf.append(data);
            if (last) {
                String text = buf.toString();
                buf.setLength(0);
                onMessage(this, text);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed = true;
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed = true;
            failed(this, "соединение Socket Mode прервано");
        }

        void close() {
            closed = true;
            WebSocket w = ws;
            if (w != null && !w.isOutputClosed()) {
                w.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(e -> null);
            }
            if (w != null) {
                w.abort();
            }
        }
    }

    /** Список нужен тестам: какие боты сейчас на связи. */
    List<UUID> connected() {
        return conns.values().stream().filter(c -> !c.closed && c.ready).map(c -> c.id).toList();
    }
}
