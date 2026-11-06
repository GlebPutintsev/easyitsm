package ru.easyitsm.integrations.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;

/** Таблицы {@code integration_messenger*}: боты, привязки, коды, очередь и связи сообщений с заявками. */
@Component
class MessengerStore {

    /** Бот без токена. */
    record Messenger(UUID id, String kind, String name, String tokenHint, String baseUrl, String botUsername,
                     boolean enabled, boolean allowTickets, String ticketType, UUID groupId, List<String> dataFields,
                     Instant lastPollAt, String lastError, Instant lastEventAt, int linkedUsers, long version,
                     Instant createdAt, Instant updatedAt) {

        boolean shares(String field) {
            return dataFields.contains(field);
        }
    }

    record Fields(String name, String baseUrl, boolean enabled, boolean allowTickets, String ticketType, UUID groupId,
                  List<String> dataFields) {
    }

    record Link(UUID id, UUID messengerId, UUID userId, String chatId, String externalName, Instant linkedAt) {
    }

    record Outgoing(UUID id, UUID messengerId, String chatId, String text, String replyTo, UUID ticketId,
                    int attempts) {
    }

    private static final String COLUMNS = """
            m.id, m.kind, m.name, m.token_hint, m.base_url, m.bot_username, m.enabled, m.allow_tickets, m.ticket_type,
            m.group_id, m.data_fields, m.last_poll_at, m.last_error, m.last_event_at, m.version, m.created_at,
            m.updated_at, (select count(*) from integration_messenger_link l where l.messenger_id = m.id) as linked""";

    private final JdbcClient jdbc;

    MessengerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- боты

    List<Messenger> list() {
        return jdbc.sql("select " + COLUMNS + " from integration_messenger m order by m.name, m.id").query(this::map)
                .list();
    }

    Optional<Messenger> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from integration_messenger m where m.id = :id").param("id", id)
                .query(this::map).optional();
    }

    Optional<String> token(UUID id) {
        return jdbc.sql("select token from integration_messenger where id = :id").param("id", id).query(String.class)
                .optional();
    }

    UUID insert(String kind, Fields f, String encryptedToken, String tokenHint, String botUsername) {
        UUID id = Ids.newId();
        var now = SourceStore.now();
        UUID actor = CurrentActor.userId().orElse(null);
        jdbc.sql("""
                insert into integration_messenger (id, tenant_id, kind, name, token, token_hint, base_url, bot_username,
                    enabled, allow_tickets, ticket_type, group_id, data_fields, created_at, created_by, updated_at,
                    updated_by, version)
                values (:id, :tenant, :kind, :name, :token, :hint, :base, :bot, :enabled, :tickets, :type, :group,
                    cast(:fields as text[]), :now, :actor, :now, :actor, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).param("kind", kind).param("name", f.name())
                .param("token", encryptedToken).param("hint", tokenHint).param("base", f.baseUrl())
                .param("bot", botUsername).param("enabled", f.enabled()).param("tickets", f.allowTickets())
                .param("type", f.ticketType()).param("group", f.groupId()).param("fields", array(f.dataFields()))
                .param("now", now).param("actor", actor).update();
        return id;
    }

    /** @param encryptedToken null — токен прежний */
    boolean update(UUID id, long version, Fields f, String encryptedToken, String tokenHint, String botUsername) {
        return jdbc.sql("""
                update integration_messenger set name = :name, base_url = :base, enabled = :enabled,
                       allow_tickets = :tickets, ticket_type = :type, group_id = :group,
                       data_fields = cast(:fields as text[]), token = coalesce(cast(:token as text), token),
                       token_hint = coalesce(cast(:hint as text), token_hint),
                       bot_username = coalesce(cast(:bot as text), bot_username),
                       poll_cursor = case when cast(:token as text) is null then poll_cursor end,
                       last_error = case when cast(:token as text) is null then last_error end,
                       updated_at = :now, updated_by = :actor, version = version + 1
                 where id = :id and version = :version""")
                .param("name", f.name()).param("base", f.baseUrl()).param("enabled", f.enabled())
                .param("tickets", f.allowTickets()).param("type", f.ticketType()).param("group", f.groupId())
                .param("fields", array(f.dataFields())).param("token", encryptedToken).param("hint", tokenHint)
                .param("bot", botUsername).param("now", SourceStore.now())
                .param("actor", CurrentActor.userId().orElse(null)).param("id", id).param("version", version)
                .update() == 1;
    }

    boolean delete(UUID id) {
        return jdbc.sql("delete from integration_messenger where id = :id").param("id", id).update() == 1;
    }

    /** Все арендаторы: включённые боты (функция SECURITY DEFINER). */
    List<SourceStore.TokenMatch> enabledEverywhere() {
        return jdbc.sql("select tenant_id, id from integration_messengers_enabled()")
                .query((rs, n) -> new SourceStore.TokenMatch(rs.getObject("tenant_id", UUID.class),
                        rs.getObject("id", UUID.class)))
                .list();
    }

    /** Блокировка опроса бота на время транзакции: опрашивает один под. */
    boolean tryLockPoll(UUID id) {
        return Boolean.TRUE.equals(jdbc.sql("select pg_try_advisory_xact_lock(hashtextextended(:k, 0))")
                .param("k", "messenger-poll:" + id).query(Boolean.class).single());
    }

    Optional<String> cursor(UUID id) {
        return jdbc.sql("select poll_cursor from integration_messenger where id = :id").param("id", id)
                .query((rs, n) -> Optional.ofNullable(rs.getString(1))).optional().flatMap(o -> o);
    }

    void polled(UUID id, String cursor, String error, boolean gotEvents) {
        jdbc.sql("""
                update integration_messenger set poll_cursor = coalesce(cast(:cursor as text), poll_cursor), last_poll_at = now(),
                       last_error = cast(:error as text), last_event_at = case when :events then now() else last_event_at end
                 where id = :id""")
                .param("cursor", cursor).param("error", error == null ? null : AlertFormats.cut(error, 500))
                .param("events", gotEvents).param("id", id).update();
    }

    // ---------------------------------------------------------------- привязки и коды

    List<Link> linksOf(UUID userId) {
        return jdbc.sql("""
                select id, messenger_id, user_id, chat_id, external_name, linked_at from integration_messenger_link
                 where user_id = :u order by linked_at""").param("u", userId).query(this::link).list();
    }

    Optional<Link> linkByChat(UUID messengerId, String chatId) {
        return jdbc.sql("""
                select id, messenger_id, user_id, chat_id, external_name, linked_at from integration_messenger_link
                 where messenger_id = :m and chat_id = :c""").param("m", messengerId).param("c", chatId)
                .query(this::link).optional();
    }

    /** Привязка чата к пользователю; прежняя привязка пользователя к этому боту и этого чата заменяется. */
    void link(UUID messengerId, UUID userId, String chatId, String externalName) {
        jdbc.sql("delete from integration_messenger_link where messenger_id = :m and (user_id = :u or chat_id = :c)")
                .param("m", messengerId).param("u", userId).param("c", chatId).update();
        var now = SourceStore.now();
        jdbc.sql("""
                insert into integration_messenger_link (id, tenant_id, messenger_id, user_id, chat_id, external_name,
                    linked_at, created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :m, :u, :c, :name, :now, :now, :u, :now, :u, 0)""")
                .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("m", messengerId)
                .param("u", userId).param("c", chatId).param("name", AlertFormats.cut(externalName, 200))
                .param("now", now).update();
    }

    boolean unlink(UUID messengerId, UUID userId) {
        return jdbc.sql("delete from integration_messenger_link where messenger_id = :m and user_id = :u")
                .param("m", messengerId).param("u", userId).update() == 1;
    }

    void unlinkChat(UUID messengerId, String chatId) {
        jdbc.sql("delete from integration_messenger_link where messenger_id = :m and chat_id = :c")
                .param("m", messengerId).param("c", chatId).update();
    }

    void saveCode(UUID messengerId, UUID userId, byte[] hash, Instant expiresAt) {
        jdbc.sql("delete from integration_messenger_code where messenger_id = :m and user_id = :u")
                .param("m", messengerId).param("u", userId).update();
        var now = SourceStore.now();
        jdbc.sql("""
                insert into integration_messenger_code (id, tenant_id, messenger_id, user_id, code_hash, expires_at,
                    created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :m, :u, :h, :exp, :now, :u, :now, :u, 0)""")
                .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("m", messengerId)
                .param("u", userId).param("h", hash).param("exp", expiresAt.atOffset(ZoneOffset.UTC)).param("now", now)
                .update();
    }

    /** Код привязки: одноразовый, действует до срока. @return пользователь кода */
    Optional<UUID> consumeCode(UUID messengerId, byte[] hash) {
        return jdbc.sql("""
                delete from integration_messenger_code where messenger_id = :m and code_hash = :h
                 returning user_id, expires_at > now() as valid""")
                .param("m", messengerId).param("h", hash)
                .query((rs, n) -> rs.getBoolean("valid") ? rs.getObject("user_id", UUID.class) : null)
                .optional();
    }

    // ---------------------------------------------------------------- очередь и связи с заявками

    void enqueue(UUID messengerId, String chatId, String text, String replyTo, UUID ticketId, String ticketNumber) {
        var now = SourceStore.now();
        jdbc.sql("""
                insert into integration_messenger_outbox (id, tenant_id, messenger_id, chat_id, text, reply_to,
                    ticket_id, ticket_number, status, attempts, next_attempt_at, created_at, updated_at, version)
                values (:id, :tenant, :m, :c, :text, :reply, :t, :n, 'pending', 0, :now, :now, :now, 0)""")
                .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("m", messengerId)
                .param("c", chatId).param("text", AlertFormats.cut(text, 4000)).param("reply", replyTo)
                .param("t", ticketId).param("n", ticketNumber).param("now", now).update();
    }

    List<SourceStore.TokenMatch> due(int limit) {
        return jdbc.sql("select tenant_id, id from integration_messenger_due(:l)").param("l", limit)
                .query((rs, n) -> new SourceStore.TokenMatch(rs.getObject("tenant_id", UUID.class),
                        rs.getObject("id", UUID.class)))
                .list();
    }

    Optional<Outgoing> lockOutgoing(UUID id) {
        return jdbc.sql("""
                select id, messenger_id, chat_id, text, reply_to, ticket_id, attempts from integration_messenger_outbox
                 where id = :id and status = 'pending' and next_attempt_at <= now()
                 for update skip locked""")
                .param("id", id)
                .query((rs, n) -> new Outgoing(rs.getObject("id", UUID.class), rs.getObject("messenger_id", UUID.class),
                        rs.getString("chat_id"), rs.getString("text"), rs.getString("reply_to"),
                        rs.getObject("ticket_id", UUID.class), rs.getInt("attempts")))
                .optional();
    }

    void sent(UUID id, int attempts) {
        jdbc.sql("""
                update integration_messenger_outbox set status = 'sent', attempts = :a, sent_at = now(),
                       next_attempt_at = null, last_error = null, updated_at = now(), version = version + 1
                 where id = :id""").param("a", attempts).param("id", id).update();
    }

    /** @param next null — попытки кончились */
    void failed(UUID id, int attempts, String error, Instant next) {
        jdbc.sql("""
                update integration_messenger_outbox set status = :st, attempts = :a, last_error = :e,
                       next_attempt_at = :next, updated_at = now(), version = version + 1
                 where id = :id""")
                .param("st", next == null ? "dead" : "pending").param("a", attempts).param("e", AlertFormats.cut(error, 500))
                .param("next", next == null ? null : next.atOffset(ZoneOffset.UTC)).param("id", id).update();
    }

    void thread(UUID messengerId, String chatId, String messageId, UUID ticketId) {
        if (messageId == null || ticketId == null) {
            return;
        }
        var now = SourceStore.now();
        jdbc.sql("""
                insert into integration_messenger_thread (id, tenant_id, messenger_id, chat_id, message_id, ticket_id,
                    created_at, updated_at, version)
                values (:id, :tenant, :m, :c, :msg, :t, :now, :now, 0)
                on conflict (messenger_id, chat_id, message_id) do nothing""")
                .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("m", messengerId)
                .param("c", chatId).param("msg", messageId).param("t", ticketId).param("now", now).update();
    }

    Optional<UUID> ticketOf(UUID messengerId, String chatId, String messageId) {
        return jdbc.sql("""
                select ticket_id from integration_messenger_thread
                 where messenger_id = :m and chat_id = :c and message_id = :msg""")
                .param("m", messengerId).param("c", chatId).param("msg", messageId).query(UUID.class).optional();
    }

    boolean hasThread(UUID messengerId, String chatId, UUID ticketId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                select exists (select 1 from integration_messenger_thread
                                where messenger_id = :m and chat_id = :c and ticket_id = :t)""")
                .param("m", messengerId).param("c", chatId).param("t", ticketId).query(Boolean.class).single());
    }

    /** Заявок из этого чата за последний час: защита от потока сообщений. */
    int ticketsLastHour(UUID messengerId, String chatId) {
        return jdbc.sql("""
                select count(distinct ticket_id) from integration_messenger_thread
                 where messenger_id = :m and chat_id = :c and created_at > now() - interval '1 hour'
                   and message_id like 'in:%'""")
                .param("m", messengerId).param("c", chatId).query(Integer.class).single();
    }

    // ---------------------------------------------------------------- разбор строк

    static String array(List<String> values) {
        return values.stream().map(h -> "\"" + h.replace("\"", "") + "\"").collect(Collectors.joining(",", "{", "}"));
    }

    private Messenger map(ResultSet rs, int n) throws SQLException {
        Array a = rs.getArray("data_fields");
        List<String> fields = a == null ? List.of() : Arrays.stream((Object[]) a.getArray()).map(String::valueOf).toList();
        return new Messenger(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("name"),
                rs.getString("token_hint"), rs.getString("base_url"), rs.getString("bot_username"),
                rs.getBoolean("enabled"), rs.getBoolean("allow_tickets"), rs.getString("ticket_type"),
                rs.getObject("group_id", UUID.class), fields, SourceStore.instant(rs, "last_poll_at"),
                rs.getString("last_error"), SourceStore.instant(rs, "last_event_at"), rs.getInt("linked"),
                rs.getLong("version"), SourceStore.instant(rs, "created_at"), SourceStore.instant(rs, "updated_at"));
    }

    private Link link(ResultSet rs, int n) throws SQLException {
        return new Link(rs.getObject("id", UUID.class), rs.getObject("messenger_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("chat_id"), rs.getString("external_name"),
                SourceStore.instant(rs, "linked_at"));
    }
}
