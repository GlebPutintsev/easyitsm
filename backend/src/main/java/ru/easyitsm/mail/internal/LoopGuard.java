package ru.easyitsm.mail.internal;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Отсечение автоответов и почтовых петель (CHN-02). Письмо не создаёт заявку и не получает ответа, если:
 * <ul>
 *   <li>это наше собственное письмо (заголовок {@value #LOOP_HEADER}) или отправлено с адреса нашего ящика;</li>
 *   <li>это отчёт о недоставке: пустой Return-Path, multipart/report, отправитель mailer-daemon или postmaster;</li>
 *   <li>это автоответ: Auto-Submitted не «no», X-Autoreply, X-Autorespond, Precedence bulk/junk/list/auto_reply,
 *       X-Auto-Response-Suppress, List-Id, тема «Автоответ»/«Out of office».</li>
 * </ul>
 * Частоту писем с одного адреса проверяет {@link InboundProcessor}.
 */
final class LoopGuard {

    /** Метка наших исходящих писем. */
    static final String LOOP_HEADER = "X-EasyITSM-Loop";

    private static final Set<String> SYSTEM_SENDERS = Set.of("mailer-daemon", "postmaster", "noreply", "no-reply",
            "do-not-reply", "donotreply", "bounce", "bounces");
    private static final Set<String> BULK_PRECEDENCE = Set.of("bulk", "junk", "list", "auto_reply", "auto-reply");

    private LoopGuard() {
    }

    enum Kind { LOOP, AUTO_REPLY }

    record Hit(Kind kind, String reason) {
    }

    static Optional<Hit> check(ParsedMail mail, Collection<String> ownAddresses) {
        if (mail.header(LOOP_HEADER) != null) {
            return Optional.of(new Hit(Kind.LOOP, "письмо отправлено этой системой"));
        }
        String from = mail.fromAddress();
        if (from == null || from.isBlank()) {
            return Optional.of(new Hit(Kind.LOOP, "нет отправителя"));
        }
        if (ownAddresses.contains(from.toLowerCase(Locale.ROOT))) {
            return Optional.of(new Hit(Kind.LOOP, "отправитель — ящик приёма заявок"));
        }
        String rp = mail.returnPath();
        if (rp != null && rp.replaceAll("[\\s<>]", "").isEmpty()) {
            return Optional.of(new Hit(Kind.LOOP, "пустой Return-Path: отчёт о доставке"));
        }
        if (mail.report()) {
            return Optional.of(new Hit(Kind.LOOP, "отчёт о доставке (multipart/report)"));
        }
        String local = from.substring(0, Math.max(0, from.indexOf('@'))).toLowerCase(Locale.ROOT);
        if (SYSTEM_SENDERS.contains(local)) {
            return Optional.of(new Hit(Kind.LOOP, "служебный отправитель " + local));
        }
        String autoSubmitted = lower(mail.header("Auto-Submitted"));
        if (autoSubmitted != null && !autoSubmitted.startsWith("no")) {
            return Optional.of(new Hit(Kind.AUTO_REPLY, "Auto-Submitted: " + autoSubmitted));
        }
        if (mail.header("X-Autoreply") != null || mail.header("X-Autorespond") != null
                || mail.header("X-Autoresponse") != null) {
            return Optional.of(new Hit(Kind.AUTO_REPLY, "заголовок автоответа"));
        }
        String precedence = lower(mail.header("Precedence"));
        if (precedence != null && BULK_PRECEDENCE.contains(precedence)) {
            return Optional.of(new Hit(Kind.AUTO_REPLY, "Precedence: " + precedence));
        }
        String suppress = lower(mail.header("X-Auto-Response-Suppress"));
        if (suppress != null && (suppress.contains("all") || suppress.contains("oof") || suppress.contains("autoreply"))) {
            return Optional.of(new Hit(Kind.AUTO_REPLY, "X-Auto-Response-Suppress: " + suppress));
        }
        if (mail.header("List-Id") != null || mail.header("List-Unsubscribe") != null) {
            return Optional.of(new Hit(Kind.AUTO_REPLY, "письмо рассылки"));
        }
        String subject = lower(mail.subject());
        if (subject != null && (subject.startsWith("автоответ") || subject.startsWith("auto:")
                || subject.startsWith("automatic reply") || subject.startsWith("autoreply")
                || subject.startsWith("out of office") || subject.startsWith("вне офиса")
                || subject.startsWith("недоставленное сообщение") || subject.startsWith("undeliverable"))) {
            return Optional.of(new Hit(Kind.AUTO_REPLY, "тема автоответа"));
        }
        return Optional.empty();
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }
}
