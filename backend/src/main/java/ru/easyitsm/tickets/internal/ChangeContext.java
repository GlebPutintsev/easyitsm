package ru.easyitsm.tickets.internal;

import java.time.Instant;

/** Обстоятельства одного изменения заявки: кто, когда, почему. Из них строятся история, лента и события. */
final class ChangeContext {

    final Actor actor;
    final Instant now = Sql.now();
    /** manual, group_assigned, requester_reply, timer, approval, merge, bulk, undo */
    String trigger = "manual";
    /** Причина ожидания или отмены, {@code reopened} при возврате из решения. */
    String reasonCode;
    /** Комментарий перехода, публичный. */
    String comment;
    /** Причина ручного приоритета или другого изменения, попадает в историю. */
    String reason;
    /** Выставляется движком, когда заявка получила первую реакцию: status или reply. */
    String firstResponseVia;

    ChangeContext(Actor actor) {
        this.actor = actor;
    }

    ChangeContext trigger(String t) {
        this.trigger = t;
        return this;
    }
}
