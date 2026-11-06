package ru.easyitsm.mail.internal;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Разбор заголовка Authentication-Results (RFC 8601), который пишет почтовый сервер заказчика. Ему доверяем,
 * только если authserv-id в списке доверенных у ящика; заголовок Microsoft 365 без authserv-id принимаем
 * у ящиков Graph — его ставит сам Exchange Online.
 */
final class AuthenticationResults {

    record Verdicts(String spf, String dkim, String dmarc, String dmarcPolicy) {
    }

    private AuthenticationResults() {
    }

    /** Первый (верхний) доверенный заголовок из списка в порядке письма. */
    static Optional<Verdicts> trusted(List<String> headers, List<String> trustedIds, boolean trustUnnamed) {
        for (String h : headers) {
            String value = stripComments(h);
            String[] parts = value.split(";");
            if (parts.length == 0) {
                continue;
            }
            String first = parts[0].trim();
            boolean unnamed = first.contains("=");
            String authserv = unnamed ? null : first.split("\\s+")[0].toLowerCase(Locale.ROOT);
            boolean trust = unnamed ? trustUnnamed || trustedIds.contains("*")
                    : trustedIds.contains("*") || trustedIds.contains(authserv);
            if (!trust) {
                continue;
            }
            String spf = "none";
            String dkim = "none";
            String dmarc = "none";
            String policy = "none";
            for (int i = unnamed ? 0 : 1; i < parts.length; i++) {
                String p = parts[i].trim().toLowerCase(Locale.ROOT);
                if (p.startsWith("spf=")) {
                    spf = word(p, 4);
                } else if (p.startsWith("dkim=")) {
                    String r = word(p, 5);
                    if (!"pass".equals(dkim)) {
                        dkim = r;
                    }
                } else if (p.startsWith("dmarc=")) {
                    dmarc = word(p, 6);
                    if (p.contains("action=quarantine") || p.contains("action=oreject")) {
                        policy = p.contains("action=oreject") ? "reject" : "quarantine";
                    } else if (p.contains("action=reject")) {
                        policy = "reject";
                    } else if (p.contains("header.p=reject") || p.contains("policy.dmarc=reject")) {
                        policy = "reject";
                    } else if (p.contains("header.p=quarantine") || p.contains("policy.dmarc=quarantine")) {
                        policy = "quarantine";
                    }
                }
            }
            return Optional.of(new Verdicts(spf, dkim, dmarc, policy));
        }
        return Optional.empty();
    }

    private static String word(String part, int from) {
        String rest = part.substring(from).trim();
        int sp = rest.indexOf(' ');
        return sp < 0 ? rest : rest.substring(0, sp);
    }

    private static String stripComments(String s) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (char c : s.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
            } else if (depth == 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
