package ru.easyitsm.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Web Push: браузер расшифровывает содержимое (RFC 8291), push-сервис принимает подпись VAPID (RFC 8292). */
class WebPushTest {

    @Test
    void browserDecryptsPayload() throws Exception {
        KeyPair ua = WebPush.newKeyPair();
        byte[] uaPublic = WebPush.raw((ECPublicKey) ua.getPublic());
        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        byte[] payload = "{\"title\":\"Заявка INC-000123\"}".getBytes(StandardCharsets.UTF_8);

        byte[] body = WebPush.encrypt(payload, uaPublic, auth);

        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        assertThat(in.getInt()).isEqualTo(4096);
        byte[] asPublic = new byte[in.get()];
        in.get(asPublic);
        byte[] ct = new byte[in.remaining()];
        in.get(ct);

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(ua.getPrivate());
        ka.doPhase(WebPush.fromRaw(asPublic), true);
        byte[] ikm = WebPush.hmac(WebPush.hmac(auth, ka.generateSecret()), WebPush.concat(
                "WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic, new byte[] {1}));
        byte[] prk = WebPush.hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(WebPush.hmac(prk, WebPush.concat(
                "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 16);
        byte[] nonce = Arrays.copyOf(WebPush.hmac(prk, WebPush.concat(
                "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = c.doFinal(ct);

        assertThat(plain[plain.length - 1]).isEqualTo((byte) 2);
        assertThat(Arrays.copyOf(plain, plain.length - 1)).isEqualTo(payload);
    }

    @Test
    void vapidTokenIsSignedForPushServiceOrigin() throws Exception {
        KeyPair server = WebPush.newKeyPair();
        byte[] pub = WebPush.raw((ECPublicKey) server.getPublic());
        String header = WebPush.vapidAuthorization("https://fcm.googleapis.com/fcm/send/abc", "mailto:it@corp.example",
                server.getPrivate(), pub);
        assertThat(header).startsWith("vapid t=").contains(", k=" + WebPush.b64(pub));
        String jwt = header.substring("vapid t=".length(), header.indexOf(','));
        String[] parts = jwt.split("\\.");
        String claims = new String(WebPush.unb64(parts[1]), StandardCharsets.UTF_8);
        assertThat(claims).contains("\"aud\":\"https://fcm.googleapis.com\"").contains("mailto:it@corp.example");
        Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
        s.initVerify(WebPush.fromRaw(pub));
        s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(s.verify(WebPush.unb64(parts[2]))).isTrue();
    }

    @Test
    void onlyBrowserPushServicesAreAllowed() {
        assertThat(PushService.allowedEndpoint("https://fcm.googleapis.com/fcm/send/x")).isTrue();
        assertThat(PushService.allowedEndpoint("https://web.push.apple.com/abc")).isTrue();
        assertThat(PushService.allowedEndpoint("https://wns2-par02p.notify.windows.com/w/?token=x")).isTrue();
        assertThat(PushService.allowedEndpoint("http://fcm.googleapis.com/fcm/send/x")).isFalse();
        assertThat(PushService.allowedEndpoint("https://169.254.169.254/latest/meta-data")).isFalse();
        assertThat(PushService.allowedEndpoint("https://fcm.googleapis.com.evil.example/x")).isFalse();
        assertThat(PushService.allowedEndpoint("https://user@fcm.googleapis.com/x")).isFalse();
    }
}
