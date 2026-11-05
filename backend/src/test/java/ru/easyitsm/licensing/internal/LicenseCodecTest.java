package ru.easyitsm.licensing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Проверка подписи и формата файла лицензии (ADM-07). */
class LicenseCodecTest {

    static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");

    final LicenseCodec codec = new LicenseCodec(TrustedKeys.loadFromClasspath());

    @Test
    void acceptsFileSignedWithOpensslScript() throws Exception {
        VerifiedLicense l = codec.verify(TestLicenses.resource("licensing/fixture-license.json"));
        assertThat(l.licenseId()).isEqualTo("EI-TEST-0001");
        assertThat(l.licensee()).isEqualTo("ООО \"Тест\"");
        assertThat(l.maxAgents()).isEqualTo(25);
        assertThat(l.features()).containsExactly("ai");
        assertThat(l.notAfter()).isEqualTo(Instant.parse("2100-01-01T00:00:00Z"));
        assertThat(l.keyId()).isEqualTo("test-2026");
    }

    @Test
    void acceptsLicenseIssuedInJava() throws Exception {
        assertThat(codec.verify(TestLicenses.issue("EI-1", 10, FROM, TO)).maxAgents()).isEqualTo(10);
    }

    @Test
    void rejectsChangedPayload() {
        String original = TestLicenses.issue("EI-1", 10, FROM, TO);
        String payload = between(original, "\"payload\":\"", "\"");
        String forged = new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8)
                .replace("\"maxAgents\":10", "\"maxAgents\":1000");
        String tampered = original.replace(payload,
                Base64.getEncoder().encodeToString(forged.getBytes(StandardCharsets.UTF_8)));
        assertCode(tampered, "license_bad_signature");
    }

    @Test
    void rejectsForeignKeyUnderKnownKid() {
        byte[] payload = "{\"licenseId\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        String file = TestLicenses.envelope(TestLicenses.KID, payload, TestLicenses.sign(TestLicenses.foreignKey(), payload));
        assertCode(file, "license_bad_signature");
    }

    @Test
    void rejectsUnknownKey() {
        String file = TestLicenses.issue("EI-1", 10, FROM, TO).replace("\"kid\":\"test-2026\"", "\"kid\":\"other\"");
        assertCode(file, "license_unknown_key");
    }

    @Test
    void keyIdCannotEscapeKeyDirectory() {
        String file = TestLicenses.issue("EI-1", 10, FROM, TO).replace("\"kid\":\"test-2026\"", "\"kid\":\"../test-2026\"");
        assertCode(file, "license_unknown_key");
    }

    @Test
    void rejectsGarbage() {
        assertCode("", "license_empty");
        assertCode("not json", "license_format");
        assertCode("[]", "license_format");
        assertCode("{\"format\":\"other/1\"}", "license_format");
        assertCode("{\"format\":\"easyitsm-license/1\",\"kid\":\"test-2026\",\"payload\":\"!!!\",\"signature\":\"AA==\"}",
                "license_format");
        assertCode("x".repeat(LicenseCodec.MAX_SIZE + 1), "license_too_large");
    }

    @Test
    void rejectsSignedButInconsistentPayload() {
        assertCode(TestLicenses.issue("EI-1", 10, TO, FROM), "license_payload");
        assertCode(TestLicenses.issue("EI-1", 0, FROM, TO), "license_payload");
    }

    private void assertCode(String file, String code) {
        assertThatThrownBy(() -> codec.verify(file))
                .isInstanceOf(LicenseException.class)
                .extracting(e -> ((LicenseException) e).code())
                .isEqualTo(code);
    }

    private static String between(String s, String start, String end) {
        int from = s.indexOf(start) + start.length();
        return s.substring(from, s.indexOf(end, from));
    }
}
