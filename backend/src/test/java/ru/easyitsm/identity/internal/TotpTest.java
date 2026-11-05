package ru.easyitsm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpTest {

    static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesRfc6238Vectors() {
        // RFC 6238, приложение B (SHA-1), последние 6 цифр восьмизначного кода.
        assertThat(Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(2000000000)))).isEqualTo("279037");
    }

    @Test
    void acceptsAdjacentStepAndRejectsReplay() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        long step = Totp.stepAt(now);
        String previous = Totp.code(RFC_SECRET, step - 1);
        assertThat(Totp.verify(RFC_SECRET, previous, now, null)).hasValue(step - 1);
        assertThat(Totp.verify(RFC_SECRET, previous, now, step - 1)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now, null)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "12345", now, null)).isEmpty();
    }

    @Test
    void base32AndUri() {
        assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(Totp.otpauthUri("Easy ITSM", "ivan@demo", RFC_SECRET))
                .startsWith("otpauth://totp/Easy%20ITSM:ivan%40demo?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
                .contains("issuer=Easy%20ITSM");
    }
}
