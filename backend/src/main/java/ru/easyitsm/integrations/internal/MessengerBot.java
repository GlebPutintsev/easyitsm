package ru.easyitsm.integrations.internal;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.identity.api.UserSummary;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.NewTicket;
import ru.easyitsm.tickets.api.TicketCommands;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketRef;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.TicketType;
import ru.easyitsm.tickets.api.Visibility;

/**
 * Разговор с ботом (CHN-04): привязка по коду из профиля, новая заявка из сообщения, комментарий ответом на
 * сообщение бота о заявке, {@code /stop} — отключение. Пишет только привязанный активный пользователь; ответы
 * бота ставятся в очередь и уходят фоновым обработчиком. Выполняется в арендаторе и транзакции вызывающего.
 */
@Component
class MessengerBot {

    /** Больше заявок из одного чата за час бот не создаёт. */
    static final int MAX_TICKETS_PER_HOUR = 10;
    static final int MAX_TEXT = 4000;
    /** Код привязки, отправленный текстом (Slack) или командой {@code /start}. */
    /** Комментарий по номеру заявки в начале сообщения: «INC-000123: текст» — там, где нет ответов на сообщения. */
    private static final java.util.regex.Pattern NUMBERED =
            java.util.regex.Pattern.compile("(?s)#?([A-Z]{2,6}-\\d{3,10})[:\\s]\\s*(\\S.*)");
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("(?:/start\\s+)?([A-Za-z0-9]{20})");

    private final MessengerStore store;
    private final UserDirectory users;
    private final TicketCommands commands;
    private final TicketQueries tickets;
    private final String publicUrl;

    MessengerBot(MessengerStore store, UserDirectory users, TicketCommands commands, TicketQueries tickets,
                 @Value("${easyitsm.notifications.public-url:}") String publicUrl) {
        this.store = store;
        this.users = users;
        this.commands = commands;
        this.tickets = tickets;
        this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
    }

    /** @return что сделано, для журнала */
    String handle(MessengerStore.Messenger m, MessengerConnector.Update u) {
        String text = u.text() == null ? "" : u.text().strip();
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT);
        }
        if (u.startPayload() != null) {
            return start(m, u, u.startPayload());
        }
        Optional<UserSummary> user = store.linkByChat(m.id(), u.chatId())
                .flatMap(l -> users.findUser(l.userId())).filter(UserSummary::active);
        if (user.isEmpty()) {
            java.util.regex.Matcher code = CODE.matcher(text);
            if (code.matches()) {
                return start(m, u, code.group(1));
            }
            reply(m, u, Texts.NOT_LINKED.of(null), null, null);
            return "not_linked";
        }
        String lang = user.get().locale();
        String command = text.toLowerCase(java.util.Locale.ROOT);
        if (command.equals("/start") || command.equals("/help") || command.equals("help")) {
            reply(m, u, help(m, lang), null, null);
            return "help";
        }
        if (command.equals("/stop") || command.equals("stop")) {
            store.unlinkChat(m.id(), u.chatId());
            reply(m, u, Texts.STOPPED.of(lang), null, null);
            return "unlinked";
        }
        if (text.isEmpty()) {
            reply(m, u, Texts.EMPTY.of(lang), null, null);
            return "empty";
        }
        // Повторная доставка того же сообщения (Slack повторяет, опрос после сбоя пода) не дублирует заявку
        // и комментарий: оба запоминают id сообщения.
        if (u.messageId() != null && store.ticketOf(m.id(), u.chatId(), u.messageId()).isPresent()) {
            return "duplicate";
        }
        if (u.replyTo() != null) {
            Optional<UUID> ticketId = store.ticketOf(m.id(), u.chatId(), u.replyTo());
            if (ticketId.isPresent()) {
                return comment(m, u, user.get(), ticketId.get(), text);
            }
        }
        java.util.regex.Matcher numbered = NUMBERED.matcher(text);
        if (numbered.matches()) {
            Optional<TicketSnapshot> t = tickets.findByNumber(numbered.group(1));
            if (t.isPresent()) {
                return comment(m, u, user.get(), t.get().id(), numbered.group(2).strip());
            }
        }
        return create(m, u, user.get(), text);
    }

    private String start(MessengerStore.Messenger m, MessengerConnector.Update u, String payload) {
        Optional<UUID> owner = store.consumeCode(m.id(), MessengerService.hash(payload));
        Optional<UserSummary> user = owner.flatMap(users::findUser).filter(UserSummary::active);
        if (user.isEmpty()) {
            reply(m, u, Texts.BAD_CODE.of(null), null, null);
            return "bad_code";
        }
        store.link(m.id(), user.get().id(), u.chatId(), u.fromName());
        reply(m, u, Texts.LINKED.of(user.get().locale()).formatted(user.get().displayName()) + "\n\n"
                + help(m, user.get().locale()), null, null);
        return "linked";
    }

    private String comment(MessengerStore.Messenger m, MessengerConnector.Update u, UserSummary user, UUID ticketId,
                           String text) {
        String lang = user.locale();
        TicketSnapshot t = tickets.find(ticketId).orElse(null);
        if (t == null) {
            reply(m, u, Texts.NO_TICKET.of(lang), null, null);
            return "no_ticket";
        }
        Visibility visibility = null;
        if (user.id().equals(t.requesterId()) || user.id().equals(t.createdForId())) {
            visibility = Visibility.PUBLIC;
        } else if (user.id().equals(t.assigneeId()) || tickets.watchers(t.id()).contains(user.id())) {
            visibility = Visibility.INTERNAL;
        }
        if (visibility == null) {
            reply(m, u, Texts.NOT_PARTICIPANT.of(lang).formatted(t.number()), null, null);
            return "not_participant";
        }
        if (t.statusCategory() != null && t.statusCategory().isFinal()) {
            reply(m, u, Texts.CLOSED.of(lang).formatted(t.number()), null, null);
            return "closed";
        }
        try {
            commands.addComment(t.id(), user.id(), text, visibility);
        } catch (ApiException e) {
            reply(m, u, Texts.FAILED.of(lang), null, null);
            return "error: " + e.getMessage();
        }
        store.thread(m.id(), u.chatId(), u.messageId(), t.id());
        reply(m, u, (visibility == Visibility.PUBLIC ? Texts.COMMENTED : Texts.NOTED).of(lang).formatted(t.number()),
                t.id(), t.number());
        return "comment " + t.number();
    }

    private String create(MessengerStore.Messenger m, MessengerConnector.Update u, UserSummary user, String text) {
        String lang = user.locale();
        if (!m.allowTickets()) {
            reply(m, u, Texts.NO_TICKETS.of(lang), null, null);
            return "tickets_disabled";
        }
        if (store.ticketsLastHour(m.id(), u.chatId()) >= MAX_TICKETS_PER_HOUR) {
            reply(m, u, Texts.TOO_MANY.of(lang), null, null);
            return "rate_limited";
        }
        TicketType type = TicketType.fromCode(m.ticketType());
        String title = title(text, m.kind());
        TicketRef ref = commands.create(new NewTicket(type, title, text, user.id(), null, null, Channel.CHAT, null, null,
                null, null, null, null, m.groupId(), null, Map.of(), false, user.id()));
        store.thread(m.id(), u.chatId(), "in:" + u.messageId(), ref.id());
        store.thread(m.id(), u.chatId(), u.messageId(), ref.id());
        StringBuilder sb = new StringBuilder(Texts.CREATED.of(lang).formatted(ref.number()));
        if (m.shares("title")) {
            sb.append("\n").append(title);
        }
        if (m.shares("link") && !publicUrl.isEmpty()) {
            sb.append("\n").append(publicUrl).append("/portal/tickets/").append(ref.number());
        }
        sb.append("\n\n").append(replyHint(m.kind(), lang));
        reply(m, u, sb.toString(), ref.id(), ref.number());
        return "created " + ref.number();
    }

    /** Тема заявки — первая строка сообщения, не длиннее 120 символов. */
    static String title(String text, String kind) {
        String first = text.lines().map(String::strip).filter(s -> !s.isEmpty()).findFirst().orElse("");
        if (first.isEmpty()) {
            return "Обращение из " + switch (kind) {
                case "max" -> "MAX";
                case "slack" -> "Slack";
                case "teams" -> "Microsoft Teams";
                default -> "Telegram";
            };
        }
        return first.length() > 120 ? first.substring(0, 119) + "…" : first;
    }

    /** Подсказка под сообщением о заявке: в Slack ответ — это сообщение в ветке. */
    static String replyHint(String kind, String lang) {
        return ("slack".equals(kind) ? Texts.THREAD_HINT : Texts.REPLY_HINT).of(lang);
    }

    /** Справка: в Slack команды с «/» перехватывает сам Slack, поэтому там {@code stop} без косой черты. */
    static String help(MessengerStore.Messenger m, String lang) {
        return Texts.HELP.of(lang).formatted(Set.of("slack", "teams").contains(m.kind()) ? "stop" : "/stop");
    }

    private void reply(MessengerStore.Messenger m, MessengerConnector.Update u, String text, UUID ticketId,
                       String number) {
        store.enqueue(m.id(), u.chatId(), text, u.messageId(), ticketId, number);
    }

    /** Тексты бота: русский и английский по языку пользователя. */
    enum Texts {
        NOT_LINKED("Бот работает только для пользователей портала. Подключите его в профиле: «Профиль → Мессенджеры».",
                "This bot works only for portal users. Connect it in your profile: “Profile → Messengers”."),
        BAD_CODE("Ссылка подключения устарела или уже использована. Получите новую в профиле: «Профиль → Мессенджеры».",
                "The connection link has expired or was already used. Get a new one in “Profile → Messengers”."),
        LINKED("Готово, %s: уведомления будут приходить сюда.", "Done, %s: notifications will arrive here."),
        HELP("Напишите сообщение — создам заявку. Ответьте на сообщение о заявке или начните сообщение с её номера (INC-000123: …) — добавлю комментарий. %s — отключить бот.",
                "Send a message to create a ticket. Reply to a ticket message or start a message with its number (INC-000123: …) to add a comment. %s disconnects the bot."),
        STOPPED("Бот отключён. Подключить снова можно в профиле.", "The bot is disconnected. You can connect it again in your profile."),
        EMPTY("Пришлите текст: файлы бот пока не принимает.", "Please send text: the bot does not accept files yet."),
        NO_TICKET("Заявка не найдена.", "Ticket not found."),
        NOT_PARTICIPANT("Комментировать %s через бот может только заявитель, исполнитель или наблюдатель.",
                "Only the requester, assignee or a watcher can comment on %s via the bot."),
        CLOSED("Заявка %s закрыта. Напишите новое сообщение, чтобы создать заявку.",
                "Ticket %s is closed. Send a new message to create a ticket."),
        FAILED("Не удалось добавить комментарий. Попробуйте позже или откройте заявку на портале.",
                "Could not add the comment. Try later or open the ticket in the portal."),
        COMMENTED("Комментарий добавлен в %s.", "Comment added to %s."),
        NOTED("Внутренняя заметка добавлена в %s.", "Internal note added to %s."),
        NO_TICKETS("Заявки через этот бот не принимаются. Воспользуйтесь порталом.",
                "This bot does not accept tickets. Please use the portal."),
        TOO_MANY("Слишком много заявок за час. Попробуйте позже.", "Too many tickets in an hour. Please try later."),
        CREATED("Создана заявка %s.", "Ticket %s created."),
        REPLY_HINT("Ответьте на это сообщение, чтобы добавить комментарий.", "Reply to this message to add a comment."),
        THREAD_HINT("Ответьте в ветке этого сообщения, чтобы добавить комментарий.",
                "Reply in this message's thread to add a comment.");

        private final String ru;
        private final String en;

        Texts(String ru, String en) {
            this.ru = ru;
            this.en = en;
        }

        String of(String locale) {
            return locale != null && locale.toLowerCase(java.util.Locale.ROOT).startsWith("en") ? en : ru;
        }
    }
}
