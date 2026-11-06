package ru.easyitsm.mail.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Проверка подписей DKIM (RFC 6376) по исходным байтам письма: rsa-sha256 и ed25519-sha256 (RFC 8463),
 * канонизация simple и relaxed. rsa-sha1 не принимается (RFC 8301), ключи RSA короче 1024 бит — тоже.
 */
final class DkimVerifier {

    private static final byte[] ED25519_SPKI_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21,
            0x00};

    private final DnsResolver dns;

    DkimVerifier(DnsResolver dns) {
        this.dns = dns;
    }

    /**
     * @param result      pass, fail, none, temperror или permerror
     * @param passDomains домены d= подписей, прошедших проверку (для выравнивания DMARC)
     */
    record Result(String result, List<String> passDomains) {
    }

    Result verify(byte[] raw) {
        Message m = split(raw);
        List<Field> signatures = m.fields.stream().filter(f -> f.name.equalsIgnoreCase("DKIM-Signature")).toList();
        if (signatures.isEmpty()) {
            return new Result("none", List.of());
        }
        List<String> passed = new ArrayList<>();
        boolean temp = false;
        for (Field sig : signatures) {
            try {
                String d = verifyOne(sig, m);
                if (d != null) {
                    passed.add(d);
                }
            } catch (DnsResolver.DnsException e) {
                temp = true;
            } catch (RuntimeException | GeneralSecurityException e) {
                // неверная подпись или битый заголовок: эта подпись не проходит
            }
        }
        if (!passed.isEmpty()) {
            return new Result("pass", passed);
        }
        return new Result(temp ? "temperror" : "fail", List.of());
    }

    /** @return домен d= при успехе, null — подпись не прошла */
    private String verifyOne(Field sig, Message m) throws DnsResolver.DnsException, GeneralSecurityException {
        Map<String, String> tags = tags(sig.value());
        String algorithm = tags.getOrDefault("a", "").toLowerCase(Locale.ROOT);
        String domain = tags.getOrDefault("d", "").toLowerCase(Locale.ROOT);
        String selector = tags.get("s");
        String signedHeaders = tags.get("h");
        String bodyHash = tags.get("bh");
        String b = tags.get("b");
        if (!"1".equals(tags.get("v")) || domain.isEmpty() || selector == null || signedHeaders == null
                || bodyHash == null || b == null) {
            return null;
        }
        if (!algorithm.equals("rsa-sha256") && !algorithm.equals("ed25519-sha256")) {
            return null;
        }
        if (!List.of(signedHeaders.toLowerCase(Locale.ROOT).split("\\s*:\\s*")).contains("from")) {
            return null;
        }
        String x = tags.get("x");
        if (x != null && Long.parseLong(x.trim()) < Instant.now().getEpochSecond()) {
            return null;
        }
        String[] canon = tags.getOrDefault("c", "simple/simple").toLowerCase(Locale.ROOT).split("/");
        boolean relaxedHeader = canon[0].equals("relaxed");
        boolean relaxedBody = canon.length > 1 && canon[1].equals("relaxed");

        byte[] body = relaxedBody ? relaxedBody(m.body) : simpleBody(m.body);
        if (tags.containsKey("l")) {
            int l = Integer.parseInt(tags.get("l").trim());
            if (l > body.length) {
                return null;
            }
            body = java.util.Arrays.copyOf(body, l);
        }
        byte[] bh = MessageDigest.getInstance("SHA-256").digest(body);
        if (!MessageDigest.isEqual(bh, Base64.getDecoder().decode(strip(bodyHash)))) {
            return null;
        }

        StringBuilder data = new StringBuilder();
        Map<String, Integer> used = new HashMap<>();
        for (String name : signedHeaders.split(":")) {
            String n = name.trim().toLowerCase(Locale.ROOT);
            if (n.isEmpty()) {
                continue;
            }
            int skip = used.merge(n, 1, Integer::sum);
            Field f = nthFromBottom(m.fields, n, skip);
            if (f != null) {
                data.append(relaxedHeader ? relaxedHeader(f) : f.raw).append("\r\n");
            }
        }
        String sigNoB = sig.raw.replaceAll("(?s)(\\bb\\s*=)[^;]*", "$1");
        Field emptied = new Field(sig.name, sigNoB.substring(sigNoB.indexOf(':') + 1), sigNoB);
        data.append(relaxedHeader ? relaxedHeader(emptied) : emptied.raw);
        byte[] signed = data.toString().getBytes(StandardCharsets.ISO_8859_1);

        PublicKey key = key(domain, selector, algorithm);
        if (key == null) {
            return null;
        }
        Signature verifier;
        byte[] input;
        if (algorithm.equals("rsa-sha256")) {
            verifier = Signature.getInstance("SHA256withRSA");
            input = signed;
        } else {
            verifier = Signature.getInstance("Ed25519");
            input = MessageDigest.getInstance("SHA-256").digest(signed);
        }
        verifier.initVerify(key);
        verifier.update(input);
        return verifier.verify(Base64.getDecoder().decode(strip(b))) ? domain : null;
    }

    private PublicKey key(String domain, String selector, String algorithm)
            throws DnsResolver.DnsException, GeneralSecurityException {
        List<String> records = dns.txt(selector + "._domainkey." + domain);
        for (String record : records) {
            Map<String, String> t = tags(record);
            String p = t.get("p");
            if (p == null || strip(p).isEmpty()) {
                continue; // ключ отозван
            }
            String k = t.getOrDefault("k", "rsa").trim().toLowerCase(Locale.ROOT);
            byte[] der = Base64.getDecoder().decode(strip(p));
            if (k.equals("rsa") && algorithm.equals("rsa-sha256")) {
                PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
                if (((java.security.interfaces.RSAPublicKey) key).getModulus().bitLength() < 1024) {
                    return null;
                }
                return key;
            }
            if (k.equals("ed25519") && algorithm.equals("ed25519-sha256") && der.length == 32) {
                byte[] spki = new byte[ED25519_SPKI_PREFIX.length + 32];
                System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
                System.arraycopy(der, 0, spki, ED25519_SPKI_PREFIX.length, 32);
                return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
            }
        }
        return null;
    }

    // ================================================================ разбор и канонизация

    record Field(String name, String value, String raw) {
    }

    record Message(List<Field> fields, byte[] body) {
    }

    /** Заголовки (с исходным переносом строк) и тело. Переводы строк приводятся к CRLF. */
    static Message split(byte[] raw) {
        String s = new String(raw, StandardCharsets.ISO_8859_1).replaceAll("\r?\n", "\r\n");
        int end = s.indexOf("\r\n\r\n");
        String head = end < 0 ? s : s.substring(0, end);
        String body = end < 0 ? "" : s.substring(end + 4);
        List<Field> fields = new ArrayList<>();
        StringBuilder current = null;
        for (String line : head.split("\r\n", -1)) {
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t') && current != null) {
                current.append("\r\n").append(line);
            } else {
                if (current != null) {
                    fields.add(field(current.toString()));
                }
                current = new StringBuilder(line);
            }
        }
        if (current != null && !current.isEmpty()) {
            fields.add(field(current.toString()));
        }
        return new Message(fields, body.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static Field field(String raw) {
        int colon = raw.indexOf(':');
        if (colon < 0) {
            return new Field(raw.trim(), "", raw);
        }
        return new Field(raw.substring(0, colon).trim(), raw.substring(colon + 1), raw);
    }

    private static Field nthFromBottom(List<Field> fields, String name, int n) {
        int seen = 0;
        for (int i = fields.size() - 1; i >= 0; i--) {
            if (fields.get(i).name.equalsIgnoreCase(name) && ++seen == n) {
                return fields.get(i);
            }
        }
        return null;
    }

    static String relaxedHeader(Field f) {
        String value = f.value.replaceAll("\r\n", "").replaceAll("[ \t]+", " ").trim();
        return f.name.toLowerCase(Locale.ROOT).trim() + ":" + value;
    }

    static byte[] simpleBody(byte[] body) {
        String s = new String(body, StandardCharsets.ISO_8859_1);
        while (s.endsWith("\r\n\r\n")) {
            s = s.substring(0, s.length() - 2);
        }
        if (s.isEmpty() || s.equals("\r\n")) {
            return "\r\n".getBytes(StandardCharsets.ISO_8859_1);
        }
        if (!s.endsWith("\r\n")) {
            s = s + "\r\n";
        }
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] relaxedBody(byte[] body) {
        String s = new String(body, StandardCharsets.ISO_8859_1);
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\r\n", -1)) {
            sb.append(line.replaceAll("[ \t]+", " ").replaceAll(" $", "")).append("\r\n");
        }
        String out = sb.toString();
        while (out.endsWith("\r\n\r\n")) {
            out = out.substring(0, out.length() - 2);
        }
        if (out.equals("\r\n")) {
            out = "";
        }
        return out.getBytes(StandardCharsets.ISO_8859_1);
    }

    static Map<String, String> tags(String list) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (String part : list.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                tags.put(part.substring(0, eq).trim().toLowerCase(Locale.ROOT), part.substring(eq + 1).trim());
            }
        }
        return tags;
    }

    private static String strip(String s) {
        return s.replaceAll("[\\s]", "");
    }
}
