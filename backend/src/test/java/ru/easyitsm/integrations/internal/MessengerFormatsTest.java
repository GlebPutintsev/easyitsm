package ru.easyitsm.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.easyitsm.notifications.api.ExternalNotice;
import tools.jackson.databind.json.JsonMapper;

/** Разбор ответов Telegram и MAX, текст уведомления по перечню передаваемых данных (CHN-06). */
class MessengerFormatsTest {

    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void telegramUpdatesPrivateChatsOnlyWithStartAndReply() {
        var poll = TelegramConnector.parse(json.readTree("""
                [{"update_id": 41, "message": {"message_id": 7, "chat": {"id": 555, "type": "private"},
                   "from": {"id": 555, "first_name": "Иван", "last_name": "Петров"}, "text": "/start AbC123"}},
                 {"update_id": 42, "message": {"message_id": 8, "chat": {"id": -100, "type": "group"}, "text": "привет"}},
                 {"update_id": 43, "message": {"message_id": 9, "chat": {"id": 555, "type": "private"},
                   "from": {"id": 555, "username": "ivan"}, "text": "Уже лучше",
                   "reply_to_message": {"message_id": 1001}}},
                 {"update_id": 44, "edited_message": {"message_id": 9}}]"""), null);
        assertThat(poll.cursor()).isEqualTo("45");
        assertThat(poll.updates()).hasSize(2);
        var start = poll.updates().get(0);
        assertThat(start.chatId()).isEqualTo("555");
        assertThat(start.startPayload()).isEqualTo("AbC123");
        assertThat(start.fromName()).isEqualTo("Иван Петров");
        var reply = poll.updates().get(1);
        assertThat(reply.replyTo()).isEqualTo("1001");
        assertThat(reply.messageId()).isEqualTo("9");
        assertThat(reply.fromName()).isEqualTo("ivan");
        assertThat(TelegramConnector.parse(json.readTree("[]"), "45").cursor()).isEqualTo("45");
    }

    @Test
    void maxUpdatesBotStartedAndDialogMessages() {
        var poll = MaxConnector.parse(json.readTree("""
                {"updates": [
                  {"update_type": "bot_started", "chat_id": 9001, "user": {"user_id": 1, "name": "Анна"}, "payload": "Code42"},
                  {"update_type": "message_created", "message": {"sender": {"user_id": 1, "name": "Анна"},
                     "recipient": {"chat_id": 9001, "chat_type": "dialog"}, "body": {"mid": "mid.2", "text": "Нет VPN"},
                     "link": {"type": "reply", "message": {"mid": "mid.1"}}}},
                  {"update_type": "message_created", "message": {"recipient": {"chat_id": 5, "chat_type": "chat"},
                     "body": {"mid": "mid.3", "text": "в группе"}}},
                  {"update_type": "message_removed"}],
                 "marker": 777}"""), "700");
        assertThat(poll.cursor()).isEqualTo("777");
        assertThat(poll.updates()).hasSize(2);
        assertThat(poll.updates().get(0).startPayload()).isEqualTo("Code42");
        assertThat(poll.updates().get(0).chatId()).isEqualTo("9001");
        var msg = poll.updates().get(1);
        assertThat(msg.text()).isEqualTo("Нет VPN");
        assertThat(msg.messageId()).isEqualTo("mid.2");
        assertThat(msg.replyTo()).isEqualTo("mid.1");
        assertThat(MaxConnector.parse(json.readTree("{\"updates\": []}"), "700").cursor()).isEqualTo("700");
    }

    @Test
    void slackDirectMessagesFromPeopleOnlyWithThreads() {
        var dm = SlackConnector.parse(json.readTree("""
                {"type": "events_api", "envelope_id": "e1", "payload": {"event": {"type": "message", "channel_type": "im",
                  "channel": "D01", "user": "U42", "text": "Не работает &lt;VPN&gt; &amp; почта", "ts": "1700000000.000100"}}}"""));
        assertThat(dm).isNotNull();
        assertThat(dm.chatId()).isEqualTo("D01");
        assertThat(dm.messageId()).isEqualTo("1700000000.000100");
        assertThat(dm.text()).isEqualTo("Не работает <VPN> & почта");
        assertThat(dm.replyTo()).isNull();
        var inThread = SlackConnector.parse(json.readTree("""
                {"payload": {"event": {"type": "message", "channel_type": "im", "channel": "D01", "user": "U42",
                  "text": "Уже лучше", "ts": "1700000000.000300", "thread_ts": "1700000000.000200"}}}"""));
        assertThat(inThread.replyTo()).isEqualTo("1700000000.000200");
        assertThat(SlackConnector.parse(json.readTree("""
                {"payload": {"event": {"type": "message", "channel_type": "im", "channel": "D01", "bot_id": "B1",
                  "text": "эхо бота", "ts": "1"}}}"""))).isNull();
        assertThat(SlackConnector.parse(json.readTree("""
                {"payload": {"event": {"type": "message", "subtype": "message_changed", "channel_type": "im",
                  "channel": "D01", "ts": "2"}}}"""))).isNull();
        assertThat(SlackConnector.parse(json.readTree("""
                {"payload": {"event": {"type": "message", "channel_type": "channel", "channel": "C01", "user": "U42",
                  "text": "в канале", "ts": "3"}}}"""))).isNull();
        assertThat(SlackConnector.escape("a < b & c")).isEqualTo("a &lt; b &amp; c");
    }

    @Test
    void teamsPersonalMessagesWithQuotedReplyAndTrustedServiceUrl() {
        var m = TeamsConnector.parse(json.readTree("""
                {"type": "message", "id": "1700", "conversation": {"conversationType": "personal", "id": "a:1xyz"},
                 "from": {"name": "Анна"}, "text": "<at>Бот</at> Уже <b>лучше</b>&nbsp;&amp; быстрее",
                 "attachments": [{"contentType": "text/html",
                   "content": "<blockquote itemscope itemtype=\\"http://schema.skype.com/Reply\\" itemid=\\"1690\\">…</blockquote>"}]}"""));
        assertThat(m.chatId()).isEqualTo("a:1xyz");
        assertThat(m.text()).isEqualTo("Уже лучше & быстрее");
        assertThat(m.replyTo()).isEqualTo("1690");
        assertThat(TeamsConnector.parse(json.readTree("""
                {"type": "message", "id": "1", "conversation": {"conversationType": "channel", "id": "19:c"}, "text": "x"}""")))
                .isNull();
        assertThat(TeamsConnector.parse(json.readTree("{\"type\": \"conversationUpdate\"}"))).isNull();
        assertThat(TeamsConnector.serviceUri("https://smba.trafficmanager.net/emea/", false)).isNotNull();
        assertThat(TeamsConnector.serviceUri("https://europe.botframework.com/", false)).isNotNull();
        assertThat(TeamsConnector.serviceUri("https://evil.example.com/", false)).isNull();
        assertThat(TeamsConnector.serviceUri("http://smba.trafficmanager.net/", false)).isNull();
        assertThat(TeamsConnector.serviceUri("http://127.0.0.1:8080/", false)).isNull();
        assertThat(TeamsConnector.serviceUri("https://smba.trafficmanager.net.evil.ru/", false)).isNull();
    }

    @Test
    void slackNoticeAsksForThreadReply() {
        ExternalNotice n = new ExternalNotice(UUID.randomUUID(), "en", "ticket.comment", "INC-000042: VPN", null,
                UUID.randomUUID(), "INC-000042", "VPN", null, false);
        assertThat(MessengerNotices.render(bot("slack", List.of("title")), n)).endsWith("Reply in this message's thread to add a comment.");
    }

    @Test
    void noticeFollowsDataFields() {
        ExternalNotice n = new ExternalNotice(UUID.randomUUID(), "ru", "ticket.comment", "INC-000042: Не работает VPN",
                "Перезагрузите ноутбук", UUID.randomUUID(), "INC-000042", "Не работает VPN",
                "https://itsm.example.ru/portal/tickets/INC-000042", false);
        String all = MessengerNotices.render(bot(List.of("title", "text", "link")), n);
        assertThat(all).contains("INC-000042: Не работает VPN", "Перезагрузите ноутбук",
                "https://itsm.example.ru/portal/tickets/INC-000042", "Ответьте на это сообщение");
        String bare = MessengerNotices.render(bot(List.of()), n);
        assertThat(bare).startsWith("Заявка INC-000042: новое уведомление")
                .doesNotContain("VPN", "Перезагрузите", "https://");
    }

    @Test
    void ticketTitleIsFirstNonEmptyLine() {
        assertThat(MessengerBot.title("\n  Не работает принтер  \nНа 3 этаже", "telegram")).isEqualTo("Не работает принтер");
        assertThat(MessengerBot.title("x".repeat(200), "max")).hasSize(120).endsWith("…");
        assertThat(MessengerBot.title("   ", "max")).isEqualTo("Обращение из MAX");
        assertThat(MessengerBot.title("   ", "slack")).isEqualTo("Обращение из Slack");
    }

    static MessengerStore.Messenger bot(List<String> fields) {
        return bot("telegram", fields);
    }

    static MessengerStore.Messenger bot(String kind, List<String> fields) {
        return new MessengerStore.Messenger(UUID.randomUUID(), kind, "Бот", "…abcd", null, null, "easy_bot", null, null, null, true,
                true, "incident", null, fields, null, null, null, 0, 0, Instant.now(), Instant.now());
    }
}
