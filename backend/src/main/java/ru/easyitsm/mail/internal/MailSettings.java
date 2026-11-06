package ru.easyitsm.mail.internal;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;

/**
 * Настройки отправки и ящиков приёма арендатора. Пароли и секреты клиентов только записываются: в ответах API
 * есть лишь признак {@code secretSet}.
 */
@Service
class MailSettings {

    private final JdbcClient jdbc;
    private final SecretBox secrets;
    private final MailProperties props;

    MailSettings(JdbcClient jdbc, SecretBox secrets, MailProperties props) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.props = props;
    }

    // ================================================================ модели

    /** Настройка отправки с расшифрованным секретом — только для транспорта, наружу не отдаётся. */
    record Transport(UUID id, String protocol, String host, Integer port, String security, String username,
                     String secret, String graphTenant, String graphClientId, String fromAddress, String fromName,
                     String replyTo, boolean enabled, long version, boolean fallback) {

        TransportView view() {
            return new TransportView(id, protocol, host, port, security, username, secret != null && !secret.isEmpty(),
                    graphTenant, graphClientId, fromAddress, fromName, replyTo, enabled, version, fallback);
        }

        String domain() {
            int at = fromAddress.lastIndexOf('@');
            return at < 0 ? "easyitsm.local" : fromAddress.substring(at + 1).toLowerCase();
        }
    }

    record TransportView(UUID id, String protocol, String host, Integer port, String security, String username,
                         boolean secretSet, String graphTenant, String graphClientId, String fromAddress,
                         String fromName, String replyTo, boolean enabled, long version, boolean fallback) {
    }

    record TransportInput(
            @NotNull @Pattern(regexp = "smtp|graph") String protocol,
            @Size(max = 255) String host,
            @Min(1) @Max(65535) Integer port,
            @Pattern(regexp = "none|starttls|ssl") String security,
            @Size(max = 320) String username,
            /* null — не менять, пустая строка — стереть */
            @Size(max = 4000) String secret,
            @Size(max = 255) String graphTenant,
            @Size(max = 255) String graphClientId,
            @NotBlank @Email @Size(max = 320) String fromAddress,
            @Size(max = 255) String fromName,
            @Email @Size(max = 320) String replyTo,
            Boolean enabled) {
    }

    record Mailbox(UUID id, String address, String protocol, String host, Integer port, String security,
                   String username, String secret, String folder, String graphTenant, String graphClientId,
                   String ticketType, UUID groupId, String authPolicy, String trustedAuthserv, boolean enabled,
                   Instant lastPolledAt, String lastError, long version) {

        MailboxView view() {
            return new MailboxView(id, address, protocol, host, port, security, username,
                    secret != null && !secret.isEmpty(), folder, graphTenant, graphClientId, ticketType, groupId,
                    authPolicy, trustedAuthserv, enabled, lastPolledAt, lastError, version);
        }

        List<String> trustedAuthservIds() {
            return trustedAuthserv == null || trustedAuthserv.isBlank() ? List.of()
                    : java.util.Arrays.stream(trustedAuthserv.split("[,\\s]+")).filter(s -> !s.isBlank())
                            .map(String::toLowerCase).toList();
        }
    }

    record MailboxView(UUID id, String address, String protocol, String host, Integer port, String security,
                       String username, boolean secretSet, String folder, String graphTenant, String graphClientId,
                       String ticketType, UUID groupId, String authPolicy, String trustedAuthserv, boolean enabled,
                       Instant lastPolledAt, String lastError, long version) {
    }

    record MailboxInput(
            @NotBlank @Email @Size(max = 320) String address,
            @NotNull @Pattern(regexp = "imap|graph|smtp") String protocol,
            @Size(max = 255) String host,
            @Min(1) @Max(65535) Integer port,
            @Pattern(regexp = "none|starttls|ssl") String security,
            @Size(max = 320) String username,
            @Size(max = 4000) String secret,
            @Size(max = 255) String folder,
            @Size(max = 255) String graphTenant,
            @Size(max = 255) String graphClientId,
            @Pattern(regexp = "incident|request") String ticketType,
            UUID groupId,
            @Pattern(regexp = "enforce|strict|monitor") String authPolicy,
            @Size(max = 1000) String trustedAuthserv,
            Boolean enabled) {
    }

    // ================================================================ отправка

    /** Настройка арендатора, иначе отправка по умолчанию из свойств установки. */
    @Transactional(readOnly = true)
    public Optional<Transport> transport() {
        Optional<Transport> own = jdbc.sql("select * from mail_transport").query(this::mapTransport).optional();
        if (own.isPresent()) {
            return own.filter(Transport::enabled);
        }
        var f = props.fallback();
        if (!f.configured()) {
            return Optional.empty();
        }
        return Optional.of(new Transport(null, "smtp", f.host(), f.port() == null ? 25 : f.port(),
                f.security() == null ? "none" : f.security(), f.username(), f.password(), null, null,
                f.fromAddress(), f.fromName() == null ? "" : f.fromName(), null, true, 0, true));
    }

    @Transactional(readOnly = true)
    public Optional<TransportView> transportView() {
        Optional<Transport> own = jdbc.sql("select * from mail_transport").query(this::mapTransport).optional();
        return own.or(this::transport).map(Transport::view);
    }

    @Transactional
    public TransportView saveTransport(TransportInput in, String ifMatch) {
        validateTransport(in);
        Optional<Transport> existing = jdbc.sql("select * from mail_transport").query(this::mapTransport).optional();
        UUID actor = CurrentActor.userId().orElse(null);
        Instant now = Instant.now();
        if (existing.isEmpty()) {
            jdbc.sql("""
                    insert into mail_transport (id, tenant_id, protocol, host, port, security, username, secret_enc,
                        graph_tenant, graph_client_id, from_address, from_name, reply_to, enabled,
                        created_at, created_by, updated_at, updated_by, version)
                    values (:id, :tenant, :protocol, :host, :port, :security, :username, :secret, :graphTenant,
                        :graphClientId, :from, :fromName, :replyTo, :enabled, :now, :actor, :now, :actor, 0)""")
                    .param("id", Ids.newId()).param("tenant", TenantContext.require())
                    .param("protocol", in.protocol()).param("host", blankToNull(in.host())).param("port", in.port())
                    .param("security", in.security() == null ? "starttls" : in.security())
                    .param("username", blankToNull(in.username())).param("secret", secrets.encrypt(in.secret()))
                    .param("graphTenant", blankToNull(in.graphTenant()))
                    .param("graphClientId", blankToNull(in.graphClientId()))
                    .param("from", in.fromAddress().trim()).param("fromName", nz(in.fromName()))
                    .param("replyTo", blankToNull(in.replyTo())).param("enabled", in.enabled() == null || in.enabled())
                    .param("now", Timestamp.from(now)).param("actor", actor)
                    .update();
        } else {
            Transport t = existing.get();
            ETags.requireMatch(ifMatch, t.version());
            String secret = in.secret() == null ? encryptedOf("mail_transport", t.id()) : secrets.encrypt(in.secret());
            jdbc.sql("""
                    update mail_transport set protocol = :protocol, host = :host, port = :port, security = :security,
                        username = :username, secret_enc = :secret, graph_tenant = :graphTenant,
                        graph_client_id = :graphClientId, from_address = :from, from_name = :fromName,
                        reply_to = :replyTo, enabled = :enabled, updated_at = :now, updated_by = :actor,
                        version = version + 1
                     where id = :id""")
                    .param("id", t.id())
                    .param("protocol", in.protocol()).param("host", blankToNull(in.host())).param("port", in.port())
                    .param("security", in.security() == null ? t.security() : in.security())
                    .param("username", blankToNull(in.username())).param("secret", secret)
                    .param("graphTenant", blankToNull(in.graphTenant()))
                    .param("graphClientId", blankToNull(in.graphClientId()))
                    .param("from", in.fromAddress().trim()).param("fromName", nz(in.fromName()))
                    .param("replyTo", blankToNull(in.replyTo()))
                    .param("enabled", in.enabled() == null ? t.enabled() : in.enabled())
                    .param("now", Timestamp.from(now)).param("actor", actor)
                    .update();
        }
        return transportView().orElseThrow();
    }

    private static void validateTransport(TransportInput in) {
        if ("smtp".equals(in.protocol()) && (in.host() == null || in.host().isBlank())) {
            throw field("host", "Укажите адрес SMTP-сервера");
        }
        if ("graph".equals(in.protocol())
                && (blankToNull(in.graphTenant()) == null || blankToNull(in.graphClientId()) == null)) {
            throw field("graphClientId", "Для Microsoft Graph нужны каталог (tenant) и id приложения");
        }
    }

    // ================================================================ ящики

    @Transactional(readOnly = true)
    public List<MailboxView> mailboxes() {
        return jdbc.sql("select * from mail_mailbox order by lower(address)").query(this::mapMailbox).list().stream()
                .map(Mailbox::view).toList();
    }

    @Transactional(readOnly = true)
    public Optional<Mailbox> mailbox(UUID id) {
        return jdbc.sql("select * from mail_mailbox where id = :id").param("id", id).query(this::mapMailbox).optional();
    }

    Mailbox requireMailbox(UUID id) {
        return mailbox(id).orElseThrow(() -> ApiException.notFound("Почтовый ящик"));
    }

    /** Адреса ящиков приёма арендатора: на них не отправляем и письма с них не принимаем (петли). */
    @Transactional(readOnly = true)
    public List<String> ownAddresses() {
        return jdbc.sql("select lower(address) from mail_mailbox").query(String.class).list();
    }

    @Transactional
    public MailboxView createMailbox(MailboxInput in) {
        validateMailbox(in);
        UUID id = Ids.newId();
        UUID actor = CurrentActor.userId().orElse(null);
        Timestamp now = Timestamp.from(Instant.now());
        try {
            jdbc.sql("""
                    insert into mail_mailbox (id, tenant_id, address, protocol, host, port, security, username, secret_enc,
                        folder, graph_tenant, graph_client_id, ticket_type, group_id, auth_policy, trusted_authserv,
                        enabled, created_at, created_by, updated_at, updated_by, version)
                    values (:id, :tenant, :address, :protocol, :host, :port, :security, :username, :secret, :folder,
                        :graphTenant, :graphClientId, :ticketType, :groupId, :authPolicy, :trusted, :enabled,
                        :now, :actor, :now, :actor, 0)""")
                    .param("id", id).param("tenant", TenantContext.require())
                    .param("address", in.address().trim()).param("protocol", in.protocol())
                    .param("host", blankToNull(in.host())).param("port", in.port())
                    .param("security", in.security() == null ? "ssl" : in.security())
                    .param("username", blankToNull(in.username())).param("secret", secrets.encrypt(in.secret()))
                    .param("folder", in.folder() == null || in.folder().isBlank() ? "INBOX" : in.folder().trim())
                    .param("graphTenant", blankToNull(in.graphTenant()))
                    .param("graphClientId", blankToNull(in.graphClientId()))
                    .param("ticketType", in.ticketType() == null ? "incident" : in.ticketType())
                    .param("groupId", in.groupId())
                    .param("authPolicy", in.authPolicy() == null ? "enforce" : in.authPolicy())
                    .param("trusted", nz(in.trustedAuthserv())).param("enabled", in.enabled() == null || in.enabled())
                    .param("now", now).param("actor", actor)
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("mailbox_exists", "Ящик с таким адресом уже есть");
        }
        return requireMailbox(id).view();
    }

    @Transactional
    public MailboxView updateMailbox(UUID id, MailboxInput in, String ifMatch) {
        Mailbox m = requireMailbox(id);
        ETags.requireMatch(ifMatch, m.version());
        validateMailbox(in);
        String secret = in.secret() == null ? encryptedOf("mail_mailbox", id) : secrets.encrypt(in.secret());
        try {
            jdbc.sql("""
                    update mail_mailbox set address = :address, protocol = :protocol, host = :host, port = :port,
                        security = :security, username = :username, secret_enc = :secret, folder = :folder,
                        graph_tenant = :graphTenant, graph_client_id = :graphClientId, ticket_type = :ticketType,
                        group_id = :groupId, auth_policy = :authPolicy, trusted_authserv = :trusted,
                        enabled = :enabled, last_error = null, updated_at = :now, updated_by = :actor,
                        version = version + 1
                     where id = :id""")
                    .param("id", id).param("address", in.address().trim()).param("protocol", in.protocol())
                    .param("host", blankToNull(in.host())).param("port", in.port())
                    .param("security", in.security() == null ? m.security() : in.security())
                    .param("username", blankToNull(in.username())).param("secret", secret)
                    .param("folder", in.folder() == null || in.folder().isBlank() ? m.folder() : in.folder().trim())
                    .param("graphTenant", blankToNull(in.graphTenant()))
                    .param("graphClientId", blankToNull(in.graphClientId()))
                    .param("ticketType", in.ticketType() == null ? m.ticketType() : in.ticketType())
                    .param("groupId", in.groupId())
                    .param("authPolicy", in.authPolicy() == null ? m.authPolicy() : in.authPolicy())
                    .param("trusted", in.trustedAuthserv() == null ? m.trustedAuthserv() : in.trustedAuthserv())
                    .param("enabled", in.enabled() == null ? m.enabled() : in.enabled())
                    .param("now", Timestamp.from(Instant.now())).param("actor", CurrentActor.userId().orElse(null))
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("mailbox_exists", "Ящик с таким адресом уже есть");
        }
        return requireMailbox(id).view();
    }

    @Transactional
    public void deleteMailbox(UUID id) {
        requireMailbox(id);
        jdbc.sql("delete from mail_mailbox where id = :id").param("id", id).update();
    }

    /** Итог опроса ящика. Версия записи не меняется: это служебное поле, а не правка администратора. */
    @Transactional
    public void markPolled(UUID id, String error) {
        jdbc.sql("update mail_mailbox set last_polled_at = now(), last_error = :error where id = :id")
                .param("id", id).param("error", error == null ? null : truncate(error, 1000)).update();
    }

    private static void validateMailbox(MailboxInput in) {
        if ("imap".equals(in.protocol())) {
            if (blankToNull(in.host()) == null) {
                throw field("host", "Укажите адрес IMAP-сервера");
            }
            if (blankToNull(in.username()) == null) {
                throw field("username", "Укажите имя пользователя ящика");
            }
        }
        if ("graph".equals(in.protocol())
                && (blankToNull(in.graphTenant()) == null || blankToNull(in.graphClientId()) == null)) {
            throw field("graphClientId", "Для Microsoft Graph нужны каталог (tenant) и id приложения");
        }
    }

    // ================================================================ служебное

    private String encryptedOf(String table, UUID id) {
        return jdbc.sql("select secret_enc from " + table + " where id = :id").param("id", id).query(String.class)
                .optional().orElse(null);
    }

    private Transport mapTransport(ResultSet rs, int n) throws SQLException {
        return new Transport(rs.getObject("id", UUID.class), rs.getString("protocol"), rs.getString("host"),
                (Integer) rs.getObject("port"), rs.getString("security"), rs.getString("username"),
                secrets.decryptOrNull(rs.getString("secret_enc")), rs.getString("graph_tenant"),
                rs.getString("graph_client_id"), rs.getString("from_address"), rs.getString("from_name"),
                rs.getString("reply_to"), rs.getBoolean("enabled"), rs.getLong("version"), false);
    }

    private Mailbox mapMailbox(ResultSet rs, int n) throws SQLException {
        Timestamp polled = rs.getTimestamp("last_polled_at");
        return new Mailbox(rs.getObject("id", UUID.class), rs.getString("address"), rs.getString("protocol"),
                rs.getString("host"), (Integer) rs.getObject("port"), rs.getString("security"),
                rs.getString("username"), secrets.decryptOrNull(rs.getString("secret_enc")), rs.getString("folder"),
                rs.getString("graph_tenant"), rs.getString("graph_client_id"), rs.getString("ticket_type"),
                rs.getObject("group_id", UUID.class), rs.getString("auth_policy"), rs.getString("trusted_authserv"),
                rs.getBoolean("enabled"), polled == null ? null : polled.toInstant(), rs.getString("last_error"),
                rs.getLong("version"));
    }

    static ApiException field(String field, String detail) {
        return ApiException.badRequest("invalid_mail_settings", detail + " (" + field + ")");
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
