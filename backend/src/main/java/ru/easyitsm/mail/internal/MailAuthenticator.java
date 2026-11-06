package ru.easyitsm.mail.internal;

import org.springframework.stereotype.Component;

/**
 * Проверка подлинности входящего письма (CHN-02): SPF, DKIM, DMARC и решение по политике ящика.
 * <ul>
 *   <li>Если у ящика есть доверенный почтовый сервер и в письме его Authentication-Results — берём его итог.</li>
 *   <li>Иначе проверяем сами: DKIM по подписи письма, SPF — только для входящего SMTP (известен адрес клиента),
 *       DMARC — по выравниванию и политике домена From.</li>
 * </ul>
 * Политика ящика: {@code enforce} — как просит DMARC домена (reject — отклонить, quarantine — в карантин), а при
 * провале SPF без подписи DKIM — карантин; {@code strict} — любой провал DMARC отклоняется, письмо без
 * пройденных SPF и DKIM — в карантин; {@code monitor} — только запись в журнал.
 */
@Component
class MailAuthenticator {

    private final DnsResolver dns;

    MailAuthenticator(DnsResolver dns) {
        this.dns = dns;
    }

    enum Decision { ACCEPT, QUARANTINE, REJECT }

    record Outcome(String spf, String dkim, String dmarc, Decision decision, String detail) {
    }

    /** Данные SMTP-сессии, если письмо пришло на встроенный входящий SMTP. */
    record Envelope(String clientIp, String mailFrom, String helo) {
    }

    Outcome check(ParsedMail mail, MailSettings.Mailbox box, Envelope envelope) {
        var trusted = AuthenticationResults.trusted(mail.headerAll("authentication-results"),
                box.trustedAuthservIds(), "graph".equals(box.protocol()));
        String spf;
        String dkim;
        String dmarc;
        String policy;
        if (trusted.isPresent()) {
            var v = trusted.get();
            spf = v.spf();
            dkim = v.dkim();
            dmarc = v.dmarc();
            policy = v.dmarcPolicy();
            if ("fail".equals(dmarc) && "none".equals(policy)) {
                // Сервер не написал политику: узнаём её сами, без повторной проверки подписей.
                policy = new DmarcEvaluator(dns).evaluate(mail.fromDomain(), "fail", null, "fail", java.util.List.of())
                        .policy();
            }
        } else {
            DkimVerifier.Result dk = new DkimVerifier(dns).verify(mail.raw());
            dkim = dk.result();
            String spfDomain = null;
            if (envelope != null && envelope.clientIp() != null) {
                String sender = envelope.mailFrom();
                spfDomain = sender != null && sender.contains("@") ? sender.substring(sender.lastIndexOf('@') + 1)
                        : envelope.helo();
                spf = new SpfChecker(dns).check(envelope.clientIp(), spfDomain, sender, envelope.helo());
            } else {
                spf = "none";
            }
            DmarcEvaluator.Result dm = new DmarcEvaluator(dns).evaluate(mail.fromDomain(), spf, spfDomain, dkim,
                    dk.passDomains());
            dmarc = dm.result();
            policy = dm.policy();
        }
        return new Outcome(spf, dkim, dmarc, decide(box.authPolicy(), spf, dkim, dmarc, policy),
                "spf=" + spf + " dkim=" + dkim + " dmarc=" + dmarc + (trusted.isPresent() ? " (сервер заказчика)" : ""));
    }

    static Decision decide(String mode, String spf, String dkim, String dmarc, String policy) {
        if ("monitor".equals(mode)) {
            return Decision.ACCEPT;
        }
        boolean dkimPass = "pass".equals(dkim);
        boolean spfPass = "pass".equals(spf);
        if ("strict".equals(mode)) {
            if ("fail".equals(dmarc)) {
                return Decision.REJECT;
            }
            return "pass".equals(dmarc) || dkimPass || spfPass ? Decision.ACCEPT : Decision.QUARANTINE;
        }
        if ("fail".equals(dmarc)) {
            return switch (policy) {
                case "reject" -> Decision.REJECT;
                case "quarantine" -> Decision.QUARANTINE;
                default -> Decision.ACCEPT;
            };
        }
        if ("fail".equals(spf) && !dkimPass && !"pass".equals(dmarc)) {
            return Decision.QUARANTINE;
        }
        return Decision.ACCEPT;
    }
}
