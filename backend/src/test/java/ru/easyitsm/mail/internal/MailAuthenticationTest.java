package ru.easyitsm.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** CHN-02: проверка SPF, DKIM, DMARC и решение по политике ящика. */
class MailAuthenticationTest {

    static KeyPair rsa;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        rsa = g.generateKeyPair();
    }

    static final String BODY = "Здравствуйте!\r\nНе работает   принтер.  \r\n\r\n\r\n";

    static String headers(String from) {
        return "From: Анна <" + from + ">\r\n"
                + "To: support@corp.example\r\n"
                + "Subject: Printer\r\n"
                + "Date: Fri, 06 Nov 2026 07:00:00 +0300\r\n"
                + "Message-ID: <m1@" + from.substring(from.indexOf('@') + 1) + ">\r\n";
    }

    /** Подписывает письмо так же, как это делает почтовый сервер отправителя (relaxed/relaxed). */
    static byte[] signed(String from, String domain, String body) throws Exception {
        String head = headers(from);
        var msg = DkimVerifier.split((head + "\r\n" + body).getBytes(StandardCharsets.UTF_8));
        byte[] bh = MessageDigest.getInstance("SHA-256").digest(DkimVerifier.relaxedBody(msg.body()));
        String sigNoB = "DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/relaxed; d=" + domain + "; s=sel;\r\n"
                + "\th=from:to:subject:date; bh=" + Base64.getEncoder().encodeToString(bh) + "; b=";
        StringBuilder data = new StringBuilder();
        for (String h : List.of("from", "to", "subject", "date")) {
            var f = msg.fields().stream().filter(x -> x.name().equalsIgnoreCase(h)).findFirst().orElseThrow();
            data.append(DkimVerifier.relaxedHeader(f)).append("\r\n");
        }
        data.append(DkimVerifier.relaxedHeader(new DkimVerifier.Field("DKIM-Signature",
                sigNoB.substring(sigNoB.indexOf(':') + 1), sigNoB)));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(rsa.getPrivate());
        s.update(data.toString().getBytes(StandardCharsets.ISO_8859_1));
        String sig = sigNoB + Base64.getEncoder().encodeToString(s.sign());
        return (sig + "\r\n" + head + "\r\n" + body).getBytes(StandardCharsets.UTF_8);
    }

    static FakeDns dnsWithKey(String domain) {
        return new FakeDns().txt("sel._domainkey." + domain,
                "v=DKIM1; k=rsa; p=" + Base64.getEncoder().encodeToString(rsa.getPublic().getEncoded()));
    }

    @Test
    void dkimPassesForUntouchedMessage() throws Exception {
        byte[] raw = signed("anna@corp.example", "corp.example", BODY);
        var r = new DkimVerifier(dnsWithKey("corp.example")).verify(raw);
        assertThat(r.result()).isEqualTo("pass");
        assertThat(r.passDomains()).containsExactly("corp.example");
    }

    @Test
    void dkimFailsWhenBodyChanged() throws Exception {
        String raw = new String(signed("anna@corp.example", "corp.example", BODY), StandardCharsets.UTF_8)
                .replace("принтер", "сканер");
        assertThat(new DkimVerifier(dnsWithKey("corp.example")).verify(raw.getBytes(StandardCharsets.UTF_8)).result())
                .isEqualTo("fail");
    }

    @Test
    void dkimNoneWithoutSignatureAndTemperrorOnDnsFailure() throws Exception {
        byte[] plain = (headers("a@corp.example") + "\r\nтекст").getBytes(StandardCharsets.UTF_8);
        assertThat(new DkimVerifier(new FakeDns()).verify(plain).result()).isEqualTo("none");
        FakeDns broken = dnsWithKey("corp.example");
        broken.fail = true;
        assertThat(new DkimVerifier(broken).verify(signed("a@corp.example", "corp.example", BODY)).result())
                .isEqualTo("temperror");
    }

    @Test
    void spfMechanisms() {
        FakeDns dns = new FakeDns()
                .txt("corp.example", "v=spf1 ip4:192.0.2.0/24 include:_spf.mailer.example mx -all")
                .txt("_spf.mailer.example", "v=spf1 ip6:2001:db8::/32 ~all")
                .mx("corp.example", "mx.corp.example").a("mx.corp.example", "198.51.100.7")
                .txt("soft.example", "v=spf1 redirect=corp.example");
        SpfChecker spf = new SpfChecker(dns);
        assertThat(spf.check("192.0.2.10", "corp.example", "a@corp.example", null)).isEqualTo("pass");
        assertThat(spf.check("2001:db8::1", "corp.example", "a@corp.example", null)).isEqualTo("pass");
        assertThat(spf.check("198.51.100.7", "corp.example", "a@corp.example", null)).isEqualTo("pass");
        assertThat(spf.check("203.0.113.5", "corp.example", "a@corp.example", null)).isEqualTo("fail");
        assertThat(spf.check("203.0.113.5", "soft.example", "a@soft.example", null)).isEqualTo("fail");
        assertThat(spf.check("203.0.113.5", "nospf.example", "a@nospf.example", null)).isEqualTo("none");
        assertThat(new SpfChecker(new FakeDns().txt("x.example", "v=spf1 -all", "v=spf1 +all"))
                .check("192.0.2.1", "x.example", null, null)).isEqualTo("permerror");
    }

    @Test
    void spfStopsAfterTenLookups() {
        FakeDns dns = new FakeDns();
        for (int i = 0; i < 12; i++) {
            dns.txt("l" + i + ".example", "v=spf1 include:l" + (i + 1) + ".example -all");
        }
        dns.txt("l12.example", "v=spf1 +all");
        assertThat(new SpfChecker(dns).check("192.0.2.1", "l0.example", null, null)).isEqualTo("permerror");
    }

    @Test
    void dmarcAlignmentAndPolicy() {
        FakeDns dns = new FakeDns().txt("_dmarc.corp.example", "v=DMARC1; p=reject; adkim=r");
        DmarcEvaluator dmarc = new DmarcEvaluator(dns);
        assertThat(dmarc.evaluate("corp.example", "none", null, "pass", List.of("mail.corp.example")).result())
                .isEqualTo("pass");
        var fail = dmarc.evaluate("corp.example", "pass", "attacker.example", "pass", List.of("attacker.example"));
        assertThat(fail.result()).isEqualTo("fail");
        assertThat(fail.policy()).isEqualTo("reject");
        // Поддомен без своей записи берёт политику организационного домена.
        assertThat(dmarc.evaluate("hr.corp.example", "none", null, "none", List.of()).policy()).isEqualTo("reject");
        assertThat(dmarc.evaluate("other.example", "none", null, "none", List.of()).result()).isEqualTo("none");
        assertThat(DmarcEvaluator.organizational("mail.corp.co.uk")).isEqualTo("corp.co.uk");
        assertThat(DmarcEvaluator.organizational("a.b.corp.ru")).isEqualTo("corp.ru");
    }

    @Test
    void authenticationResultsFromTrustedServerOnly() {
        List<String> headers = List.of(
                "mx.corp.example; spf=pass smtp.mailfrom=corp.example; dkim=fail header.d=corp.example;"
                        + " dmarc=fail (p=reject) header.from=corp.example header.p=reject",
                "evil.example; spf=pass; dkim=pass; dmarc=pass");
        var v = AuthenticationResults.trusted(headers, List.of("mx.corp.example"), false).orElseThrow();
        assertThat(v.dmarc()).isEqualTo("fail");
        assertThat(v.dmarcPolicy()).isEqualTo("reject");
        assertThat(AuthenticationResults.trusted(headers, List.of(), false)).isEmpty();
        // Microsoft 365 пишет заголовок без authserv-id: доверяем только у ящиков Graph.
        List<String> m365 = List.of("spf=pass (sender IP is 192.0.2.1) smtp.mailfrom=corp.example; dkim=pass"
                + " (signature was verified) header.d=corp.example;dmarc=pass action=none header.from=corp.example;");
        assertThat(AuthenticationResults.trusted(m365, List.of(), true).orElseThrow().dmarc()).isEqualTo("pass");
        assertThat(AuthenticationResults.trusted(m365, List.of(), false)).isEmpty();
    }

    @Test
    void policyDecisions() {
        var A = MailAuthenticator.Decision.ACCEPT;
        var Q = MailAuthenticator.Decision.QUARANTINE;
        var R = MailAuthenticator.Decision.REJECT;
        assertThat(MailAuthenticator.decide("enforce", "none", "pass", "pass", "reject")).isEqualTo(A);
        assertThat(MailAuthenticator.decide("enforce", "none", "fail", "fail", "reject")).isEqualTo(R);
        assertThat(MailAuthenticator.decide("enforce", "none", "fail", "fail", "quarantine")).isEqualTo(Q);
        assertThat(MailAuthenticator.decide("enforce", "none", "fail", "fail", "none")).isEqualTo(A);
        assertThat(MailAuthenticator.decide("enforce", "fail", "none", "none", "none")).isEqualTo(Q);
        assertThat(MailAuthenticator.decide("enforce", "none", "none", "none", "none")).isEqualTo(A);
        assertThat(MailAuthenticator.decide("strict", "none", "fail", "fail", "none")).isEqualTo(R);
        assertThat(MailAuthenticator.decide("strict", "none", "none", "none", "none")).isEqualTo(Q);
        assertThat(MailAuthenticator.decide("monitor", "fail", "fail", "fail", "reject")).isEqualTo(A);
    }
}
