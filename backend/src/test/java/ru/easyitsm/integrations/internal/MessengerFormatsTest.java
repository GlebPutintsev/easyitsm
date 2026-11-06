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
    }

    static MessengerStore.Messenger bot(List<String> fields) {
        return new MessengerStore.Messenger(UUID.randomUUID(), "telegram", "Бот", "…abcd", null, "easy_bot", true, true,
                "incident", null, fields, null, null, null, 0, 0, Instant.now(), Instant.now());
    }
}
