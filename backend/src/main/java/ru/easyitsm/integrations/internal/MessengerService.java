package ru.easyitsm.integrations.internal;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.admin.api.ConfigHistory;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;
import tools.jackson.databind.json.JsonMapper;

/**
 * Боты мессенджеров (CHN-04, CHN-06): настройка ADM и привязка пользователей из профиля. Токен проверяется
 * запросом к API мессенджера до записи в БД, поэтому сохранить нерабочий бот нельзя.
 */
@Service
class MessengerService {

    static final String HISTORY_ENTITY = "integrations.messenger";
    static final Set<String> KINDS = Set.of("telegram", "max", "slack");
    static final List<String> FIELDS = List.of("title", "text", "link");
    static final Duration CODE_TTL = Duration.ofMinutes(15);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    /** {@code appToken} — токен приложения Slack ({@code xapp-}), для других мессенджеров не нужен. */
    record MessengerInput(String kind, String name, String token, String appToken, String baseUrl, Boolean enabled,
                          Boolean allowTickets, String ticketType, UUID groupId, Boolean clearGroup,
                          List<String> dataFields) {
    }

    /** Итог проверки бота. */
    record CheckResult(boolean ok, String botUsername, String error) {
    }

    /** Бот в профиле пользователя: подключён ли он. */
    record MyMessenger(UUID id, String kind, String name, String botUsername, boolean linked, String externalName,
                       Instant linkedAt) {
    }

    /** Ссылка привязки: открывает бот (с одноразовым кодом, если мессенджер умеет); {@code command} — что отправить боту вручную. */
    record LinkCode(String url, String code, String command, Instant expiresAt) {
    }

    private final MessengerStore store;
    private final Map<String, MessengerConnector> connectors;
    private final OutboundHttp http;
    private final WebhookStore policy;
    private final WebhookSecrets secrets;
    private final ConfigHistory history;
    private final UserDirectory directory;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    MessengerService(MessengerStore store, List<MessengerConnector> connectors, OutboundHttp http, WebhookStore policy,
                     WebhookSecrets secrets, ConfigHistory history, UserDirectory directory, JsonMapper json,
                     PlatformTransactionManager txManager) {
        this.store = store;
        this.connectors = connectors.stream().collect(Collectors.toMap(MessengerConnector::kind, Function.identity()));
        this.http = http;
        this.policy = policy;
        this.secrets = secrets;
        this.history = history;
        this.directory = directory;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
    }

    // ---------------------------------------------------------------- подключение к API

    MessengerConnector connector(String kind) {
        MessengerConnector c = connectors.get(kind);
        if (c == null) {
            throw invalid("Неизвестный мессенджер " + kind);
        }
        return c;
    }

    /**
     * Подключение к API бота. Официальный адрес мессенджера разрешён всегда, свой — только из списка разрешённых
     * адресов «Интеграций»; служебные и собственные адреса сервера запрещены в любом случае.
     */
    MessengerConnector.Api api(String kind, String baseUrl, String token, String appToken, List<String> allowedHosts) {
        MessengerConnector c = connector(kind);
        String base = baseUrl == null ? c.defaultBaseUrl() : baseUrl;
        List<String> allowed = new ArrayList<>(allowedHosts);
        if (baseUrl == null) {
            allowed.add(URI.create(c.defaultBaseUrl()).getHost());
        }
        return new MessengerConnector.Api(http, http.guard(allowed), base.replaceAll("/+$", ""), token, appToken, json);
    }

    /** Подключение к API сохранённого бота в арендаторе вызывающего; null — ключ не расшифровывается. */
    MessengerConnector.Api api(MessengerStore.Messenger m) {
        String token = store.token(m.id()).map(secrets::decryptOrNull).orElse(null);
        String appToken = store.appToken(m.id()).map(secrets::decryptOrNull).orElse(null);
        if (token == null || connector(m.kind()).needsAppToken() && appToken == null) {
            return null;
        }
        return api(m.kind(), m.baseUrl(), token, appToken, policy.policy().allowedHosts());
    }

    /**
     * Адрес WebSocket Socket Mode для бота Slack. Принимается только {@code wss://*.slack.com}; узел проверяется
     * так же, как исходящие запросы (служебные и собственные адреса сервера запрещены).
     */
    URI socketUrl(MessengerStore.Messenger m) {
        MessengerConnector.Api api = api(m);
        if (api == null) {
            throw new MessengerConnector.Failure("токен не расшифровывается: укажите его заново");
        }
        URI uri = ((SlackConnector) connector("slack")).open(api);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean slack = scheme.equals("wss") && host.endsWith(".slack.com");
        boolean local = scheme.equals("ws") && http.loopbackAllowed() && OutboundGuard.isLiteral(host);
        if (!slack && !local || uri.getRawUserInfo() != null) {
            throw new MessengerConnector.Failure("Slack вернул неожиданный адрес соединения");
        }
        try {
            http.guard(List.of(host)).check(new URI(scheme.equals("wss") ? "https" : "http", null, host, uri.getPort(),
                    "/", null, null));
        } catch (java.net.URISyntaxException e) {
            throw new MessengerConnector.Failure("Slack вернул неверный адрес соединения");
        }
        return uri;
    }

    // ---------------------------------------------------------------- настройка ADM

    List<MessengerStore.Messenger> list() {
        return tx.execute(s -> store.list());
    }

    MessengerStore.Messenger get(UUID id) {
        return tx.execute(s -> require(id));
    }

    MessengerStore.Messenger create(MessengerInput in) {
        String kind = in.kind() == null ? "" : in.kind().strip().toLowerCase(Locale.ROOT);
        if (!KINDS.contains(kind)) {
            throw invalid("Мессенджер: telegram, max или slack");
        }
        String token = token(in.token());
        if (token == null) {
            throw invalid("Укажите токен бота");
        }
        boolean needsApp = connector(kind).needsAppToken();
        String appToken = needsApp ? token(in.appToken()) : null;
        if (needsApp && appToken == null) {
            throw invalid("Укажите токен приложения");
        }
        MessengerStore.Fields f = fields(in, null);
        MessengerStore.Verified bot = verify(kind, f.baseUrl(), token, appToken);
        return tx.execute(s -> {
            UUID id = store.insert(kind, f, secrets(token, appToken), bot);
            MessengerStore.Messenger m = require(id);
            record(m, "create", null);
            return m;
        });
    }

    MessengerStore.Messenger update(UUID id, String ifMatch, MessengerInput in) {
        MessengerStore.Messenger current = get(id);
        ETags.requireMatch(ifMatch, current.version());
        MessengerStore.Fields f = fields(in, current);
        String token = token(in.token());
        String appToken = connector(current.kind()).needsAppToken() ? token(in.appToken()) : null;
        MessengerStore.Verified bot = null;
        boolean addressChanged = !java.util.Objects.equals(f.baseUrl(), current.baseUrl());
        if (token != null || appToken != null || addressChanged) {
            String check = token != null ? token
                    : tx.execute(s -> store.token(id).map(secrets::decryptOrNull).orElse(null));
            String checkApp = appToken != null || !connector(current.kind()).needsAppToken() ? appToken
                    : tx.execute(s -> store.appToken(id).map(secrets::decryptOrNull).orElse(null));
            if (check == null || connector(current.kind()).needsAppToken() && checkApp == null) {
                throw invalid("Токен не расшифровывается: укажите его заново");
            }
            bot = verify(current.kind(), f.baseUrl(), check, checkApp);
        }
        MessengerStore.Verified verified = bot;
        return tx.execute(s -> {
            if (!store.update(id, current.version(), f, secrets(token, appToken), verified)) {
                throw ApiException.conflict("version_conflict", "Бот изменён другим пользователем. Обновите и повторите");
            }
            MessengerStore.Messenger m = require(id);
            record(m, "update", token == null && appToken == null ? null
                    : token != null && appToken != null ? "Новые токены бота и приложения"
                    : token != null ? "Новый токен бота" : "Новый токен приложения");
            return m;
        });
    }

    void delete(UUID id) {
        tx.executeWithoutResult(s -> {
            MessengerStore.Messenger m = require(id);
            store.delete(id);
            record(m, "delete", null);
        });
    }

    /** Проверка бота запросом к API мессенджера: токен, адрес и сеть. */
    CheckResult check(UUID id) {
        MessengerStore.Messenger m = get(id);
        MessengerConnector.Api api = tx.execute(s -> api(m));
        if (api == null) {
            return new CheckResult(false, null, "токен не расшифровывается: укажите его заново");
        }
        try {
            MessengerConnector.Bot bot = connector(m.kind()).me(api);
            return new CheckResult(true, bot.username(), null);
        } catch (MessengerConnector.Failure e) {
            return new CheckResult(false, null, e.getMessage());
        }
    }

    /** Зашифрованные токены; null — не менять. */
    private MessengerStore.Secrets secrets(String token, String appToken) {
        return new MessengerStore.Secrets(token == null ? null : secrets.encrypt(token),
                token == null ? null : Tokens.hint(token), appToken == null ? null : secrets.encrypt(appToken),
                appToken == null ? null : Tokens.hint(appToken));
    }

    private MessengerStore.Verified verify(String kind, String baseUrl, String token, String appToken) {
        List<String> allowed = tx.execute(s -> policy.policy().allowedHosts());
        MessengerConnector.Api api = api(kind, baseUrl, token, appToken, allowed);
        MessengerConnector c = connector(kind);
        try {
            MessengerConnector.Bot bot = c.me(api);
            if (bot.username() == null || bot.username().isBlank()) {
                throw new MessengerConnector.Failure("у бота нет имени пользователя");
            }
            c.prepare(api);
            return new MessengerStore.Verified(bot.username(), bot.url());
        } catch (MessengerConnector.Failure e) {
            throw ApiException.badRequest("messenger_unreachable", "Бот не отвечает: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- профиль пользователя

    List<MyMessenger> mine() {
        UUID me = CurrentActor.userId().orElseThrow(() -> ApiException.forbidden());
        return tx.execute(s -> {
            Map<UUID, MessengerStore.Link> links = store.linksOf(me).stream()
                    .collect(Collectors.toMap(MessengerStore.Link::messengerId, Function.identity(), (a, b) -> a));
            return store.list().stream().filter(MessengerStore.Messenger::enabled).map(m -> {
                MessengerStore.Link l = links.get(m.id());
                return new MyMessenger(m.id(), m.kind(), m.name(), m.botUsername(), l != null,
                        l == null ? null : l.externalName(), l == null ? null : l.linkedAt());
            }).toList();
        });
    }

    LinkCode linkCode(UUID messengerId) {
        UUID me = CurrentActor.userId().orElseThrow(() -> ApiException.forbidden());
        return tx.execute(s -> {
            MessengerStore.Messenger m = store.find(messengerId).filter(MessengerStore.Messenger::enabled)
                    .orElseThrow(() -> ApiException.notFound("Бот"));
            if (m.botUsername() == null) {
                throw ApiException.conflict("messenger_not_ready", "Бот ещё не проверен администратором");
            }
            String code = newCode();
            Instant expires = Instant.now().plus(CODE_TTL);
            store.saveCode(m.id(), me, hash(code), expires);
            MessengerConnector c = connector(m.kind());
            return new LinkCode(c.deepLink(m, code), code, c.linkCommand(code), expires);
        });
    }

    void unlinkMine(UUID messengerId) {
        UUID me = CurrentActor.userId().orElseThrow(() -> ApiException.forbidden());
        tx.executeWithoutResult(s -> {
            if (!store.unlink(messengerId, me)) {
                throw ApiException.notFound("Привязка");
            }
        });
    }

    // ---------------------------------------------------------------- проверки

    private MessengerStore.Messenger require(UUID id) {
        return store.find(id).orElseThrow(() -> ApiException.notFound("Бот"));
    }

    private MessengerStore.Fields fields(MessengerInput in, MessengerStore.Messenger current) {
        String name = in.name() == null && current != null ? current.name() : name(in.name());
        String baseUrl = in.baseUrl() == null ? (current == null ? null : current.baseUrl()) : baseUrl(in.baseUrl());
        boolean enabled = in.enabled() == null ? current == null || current.enabled() : in.enabled();
        boolean tickets = in.allowTickets() == null ? current == null || current.allowTickets() : in.allowTickets();
        String type = in.ticketType() == null ? (current == null ? "incident" : current.ticketType()) : in.ticketType();
        if (!Set.of("incident", "request").contains(type)) {
            throw invalid("Тип заявок: incident или request");
        }
        UUID group = Boolean.TRUE.equals(in.clearGroup()) ? null
                : in.groupId() != null ? in.groupId() : current == null ? null : current.groupId();
        if (group != null && in.groupId() != null && directory.findGroup(group).isEmpty()) {
            throw invalid("Группа не найдена");
        }
        List<String> fields = in.dataFields() == null ? (current == null ? List.of("title", "link") : current.dataFields())
                : dataFields(in.dataFields());
        return new MessengerStore.Fields(name, baseUrl, enabled, tickets, type, group, fields);
    }

    static List<String> dataFields(List<String> in) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String f : in) {
            if (f == null || !FIELDS.contains(f)) {
                throw invalid("Передаваемые данные: title, text, link");
            }
            out.add(f);
        }
        return FIELDS.stream().filter(out::contains).toList();
    }

    private static String name(String name) {
        if (name == null || name.isBlank()) {
            throw invalid("Укажите название");
        }
        if (name.strip().length() > 200) {
            throw invalid("Название — не длиннее 200 символов");
        }
        return name.strip();
    }

    /** Пустая строка — сбросить на официальный адрес. */
    private static String baseUrl(String url) {
        if (url.isBlank()) {
            return null;
        }
        String u = url.strip().replaceAll("/+$", "");
        try {
            URI uri = new URI(u);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw invalid("Адрес API — https:// или http:// с узлом, без логина и пароля");
            }
        } catch (java.net.URISyntaxException e) {
            throw invalid("Неверный адрес API");
        }
        if (u.length() > 500) {
            throw invalid("Адрес API — не длиннее 500 символов");
        }
        return u;
    }

    private static String token(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String t = token.strip();
        if (t.length() > 500 || t.chars().anyMatch(ch -> ch < 0x21 || ch > 0x7e)) {
            throw invalid("Токен бота — строка без пробелов");
        }
        return t;
    }

    private void record(MessengerStore.Messenger m, String action, String comment) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("kind", m.kind());
        snap.put("name", m.name());
        snap.put("baseUrl", m.baseUrl());
        snap.put("botUsername", m.botUsername());
        snap.put("enabled", m.enabled());
        snap.put("allowTickets", m.allowTickets());
        snap.put("ticketType", m.ticketType());
        snap.put("groupId", m.groupId() == null ? null : m.groupId().toString());
        snap.put("dataFields", m.dataFields());
        history.record(HISTORY_ENTITY, m.id().toString(), m.name(), action, snap, comment);
    }

    static String newCode() {
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    static byte[] hash(String code) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static ApiException invalid(String detail) {
        return ApiException.badRequest("invalid_messenger", detail);
    }

    Optional<MessengerStore.Messenger> findForJob(UUID id) {
        return store.find(id);
    }
}
