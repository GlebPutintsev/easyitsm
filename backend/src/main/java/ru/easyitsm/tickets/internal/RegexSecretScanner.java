package ru.easyitsm.tickets.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import ru.easyitsm.tickets.api.SecretFinding;
import ru.easyitsm.tickets.api.SecretScanner;

/**
 * Обнаружение секретов по шаблонам (TCK-17): ключи, токены популярных сервисов, пароли в виде
 * «пароль: …», строки подключения с паролем, номера карт с проверкой Луна.
 * Маска оставляет первые символы, чтобы человек понял, что именно скрыто, но значение не восстанавливалось.
 */
@Component
class RegexSecretScanner implements SecretScanner {

    private record Rule(String kind, Pattern pattern, int group, int keep) {
    }

    private static final int CI = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final List<Rule> RULES = List.of(
            new Rule("private_key", Pattern.compile(
                    "-----BEGIN (?:[A-Z0-9]+ )?PRIVATE KEY-----[\\s\\S]*?(?:-----END (?:[A-Z0-9]+ )?PRIVATE KEY-----|\\z)"), 0, 0),
            new Rule("aws_key", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"), 0, 4),
            new Rule("github_token", Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{40,})\\b"), 0, 4),
            new Rule("gitlab_token", Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20,}\\b"), 0, 6),
            new Rule("slack_token", Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b"), 0, 5),
            new Rule("google_api_key", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"), 0, 4),
            new Rule("yandex_token", Pattern.compile("\\b(?:y[0-3]_[A-Za-z0-9_-]{30,}|AQVN[A-Za-z0-9_-]{30,}|t1\\.[A-Za-z0-9_-]{40,}\\.[A-Za-z0-9_-]{40,})"), 0, 4),
            new Rule("jwt", Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"), 0, 3),
            new Rule("connection_string", Pattern.compile(
                    "\\b[a-z][a-z0-9+.-]{1,20}://[^\\s:/@]+:([^\\s@/]{3,})@[^\\s]+", CI), 1, 0),
            // «пароль: Qwerty» — после явного разделителя любое значение
            new Rule("password", Pattern.compile(
                    "(?<![\\p{L}])(?:password|passwd|pwd|pass|парол[ьяеи]|пасс?ворд)(?![\\p{L}])\\s*(?:[:=]|—|\\s-|\\sэто|\\sis)\\s*[\"'«]?([^\\s\"'»,;]{4,})", CI), 1, 0),
            // «мой пароль Qwerty123» — без разделителя только значение с цифрой, чтобы не ловить «пароль сменён»
            new Rule("password", Pattern.compile(
                    "(?<![\\p{L}])(?:password|passwd|pwd|парол[ьяеи]|пасс?ворд)(?![\\p{L}])\\s+[\"'«]?((?=[^\\s\"'»,;]*\\d)[^\\s\"'»,;]{4,})", CI), 1, 0),
            new Rule("token", Pattern.compile(
                    "(?:api[_-]?key|secret|token|токен|ключ(?:\\s+api)?|client[_-]?secret)\\s*[:=]\\s*[\"']?([A-Za-z0-9/+_.=-]{16,})", CI), 1, 0),
            new Rule("card_number", Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b"), 0, 0));

    @Override
    public List<SecretFinding> scan(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<SecretFinding> found = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            while (m.find()) {
                int start = m.start(rule.group());
                int end = m.end(rule.group());
                String value = text.substring(start, end);
                if (rule.kind().equals("card_number") && !luhn(value)) {
                    continue;
                }
                if (overlaps(found, start, end)) {
                    continue;
                }
                found.add(new SecretFinding(rule.kind(), start, end, mask(value, rule.keep(), rule.kind())));
            }
        }
        found.sort(Comparator.comparingInt(SecretFinding::start));
        return List.copyOf(found);
    }

    @Override
    public String mask(String text) {
        List<SecretFinding> findings = scan(text);
        if (findings.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        int pos = 0;
        for (SecretFinding f : findings) {
            sb.append(text, pos, f.start()).append(f.masked());
            pos = f.end();
        }
        return sb.append(text.substring(pos)).toString();
    }

    private static boolean overlaps(List<SecretFinding> found, int start, int end) {
        return found.stream().anyMatch(f -> start < f.end() && f.start() < end);
    }

    private static String mask(String value, int keep, String kind) {
        if (kind.equals("private_key")) {
            return "[закрытый ключ скрыт]";
        }
        if (kind.equals("card_number")) {
            String digits = value.replaceAll("\\D", "");
            return "**** **** **** " + digits.substring(digits.length() - 4);
        }
        String prefix = value.substring(0, Math.min(keep, value.length()));
        return prefix + "********";
    }

    private static boolean luhn(String value) {
        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 13 || digits.length() > 19 || digits.chars().distinct().count() < 3) {
            return false;
        }
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }
}
