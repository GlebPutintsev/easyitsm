package ru.easyitsm.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Поток 6.4: разбор письма, очистка HTML, цитаты, автоответы и токен ответа. */
class MailParsingTest {

    final MimeParser parser = new MimeParser();

    ParsedMail parse(String raw) throws Exception {
        return parser.parse(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void htmlBecomesPlainTextWithoutScriptsAndQuotes() throws Exception {
        ParsedMail m = parse("""
                From: Anna <Anna@Corp.Example>
                To: support@corp.example
                Subject: =?UTF-8?B?0J3QtSDRgNCw0LHQvtGC0LDQtdGCIFZQTg==?=
                Message-ID: <abc@corp.example>
                MIME-Version: 1.0
                Content-Type: text/html; charset=UTF-8

                <html><head><style>p{color:red}</style><script>alert(1)</script></head><body>
                <p>Не работает <b>VPN</b>, см. <a href="https://wiki.corp.example/vpn">инструкцию</a>.</p>
                <img src="https://tracker.example/pixel.gif">
                <div>On Thu, 5 Nov 2026 at 10:00, Support &lt;support@corp.example&gt; wrote:</div>
                <blockquote>старое письмо</blockquote>
                </body></html>
                """);
        assertThat(m.fromAddress()).isEqualTo("anna@corp.example");
        assertThat(m.subject()).isEqualTo("Не работает VPN");
        assertThat(m.text()).contains("Не работает VPN, см. инструкцию (https://wiki.corp.example/vpn).")
                .doesNotContain("alert").doesNotContain("color:red").doesNotContain("pixel")
                .doesNotContain("старое письмо");
    }

    @Test
    void replyMarkerAndQuotedLinesAreCut() {
        String text = "Спасибо, заработало.\n\n" + MailText.REPLY_MARKER + "\n\nВаша заявка решена";
        assertThat(MailText.stripQuoted(text)).isEqualTo("Спасибо, заработало.");
        assertThat(MailText.stripQuoted("Ответ\n\nВ пт, 6 нояб. 2026 г. в 10:00, Поддержка написал(а):\n> текст"))
                .isEqualTo("Ответ");
        assertThat(MailText.stripQuoted("Да\n> вопрос\n> ещё")).isEqualTo("Да");
    }

    @Test
    void autoRepliesAndLoopsAreDetected() throws Exception {
        String base = "From: anna@corp.example\nTo: support@corp.example\nSubject: Hi\n";
        assertThat(LoopGuard.check(parse(base + "Auto-Submitted: auto-replied\n\nx"), List.of()))
                .hasValueSatisfying(h -> assertThat(h.kind()).isEqualTo(LoopGuard.Kind.AUTO_REPLY));
        assertThat(LoopGuard.check(parse(base + "Precedence: bulk\n\nx"), List.of())).isPresent();
        assertThat(LoopGuard.check(parse(base + "X-Auto-Response-Suppress: All\n\nx"), List.of())).isPresent();
        assertThat(LoopGuard.check(parse(base + "X-EasyITSM-Loop: abc\n\nx"), List.of()))
                .hasValueSatisfying(h -> assertThat(h.kind()).isEqualTo(LoopGuard.Kind.LOOP));
        assertThat(LoopGuard.check(parse(base + "Return-Path: <>\n\nx"), List.of())).isPresent();
        assertThat(LoopGuard.check(parse("From: MAILER-DAEMON@corp.example\nSubject: x\n\nx"), List.of())).isPresent();
        assertThat(LoopGuard.check(parse("From: support@corp.example\nSubject: x\n\nx"),
                List.of("support@corp.example"))).isPresent();
        assertThat(LoopGuard.check(parse("From: anna@corp.example\nSubject: Автоответ: в отпуске\n\nx"), List.of()))
                .isPresent();
        assertThat(LoopGuard.check(parse(base + "Auto-Submitted: no\n\nx"), List.of())).isEmpty();
    }

    @Test
    void subjectIsCleaned() {
        assertThat(InboundProcessor.cleanSubject("RE: Fwd: Ответ: Не печатает [INC-000123]")).isEqualTo("Не печатает");
        assertThat(InboundProcessor.cleanSubject("Re[2]: Пароль")).isEqualTo("Пароль");
    }

    @Test
    void replyTokenLinksOnlyWithValidSignature() {
        ReplyTokens tokens = new ReplyTokens(new SecretBox(new MailProperties(
                "dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktdGVzdC0=", null, null, null, null, null, null, null, null, null),
                new MockEnvironment()));
        UUID tenant = UUID.randomUUID();
        UUID ticket = UUID.randomUUID();
        String token = tokens.token(tenant, ticket);
        assertThat(token).hasSize(20).matches("[a-z2-7]+");
        String messageId = tokens.messageId(tenant, ticket, UUID.randomUUID(), "corp.example");
        assertThat(tokens.fromHeaders(tenant, "<x@y> " + messageId)).contains(ticket);
        // Другой арендатор и подделанный токен не подходят.
        assertThat(tokens.fromHeaders(UUID.randomUUID(), messageId)).isEmpty();
        String forged = messageId.replace(token, "a".repeat(20));
        assertThat(tokens.fromHeaders(tenant, forged)).isEmpty();
        var ref = tokens.fromText("текст\n" + tokens.bodyRef(tenant, ticket, "INC-000123")).orElseThrow();
        assertThat(ref.number()).isEqualTo("INC-000123");
        assertThat(tokens.verify(tenant, ticket, ref.token())).isTrue();
    }

    @Test
    void secretsAreEncryptedAndRoundTrip() {
        SecretBox box = new SecretBox(new MailProperties("пароль-для-тестов", null, null, null, null, null, null, null,
                null, null), new MockEnvironment());
        String enc = box.encrypt("s3cret");
        assertThat(enc).startsWith("v1:").doesNotContain("s3cret");
        assertThat(box.decrypt(enc)).isEqualTo("s3cret");
        assertThat(box.encrypt("s3cret")).isNotEqualTo(enc);
    }
}
