package ru.easyitsm.audit.api;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Маскирование чувствительных данных (SEC-13, SEC-30): в журнале аудита, логах приложения, выгрузках и SIEM.
 * <p>
 * {@link #text} закрывает секреты в свободном тексте: значения после «пароль:», «token=», Bearer-токены, JWT,
 * закрытые ключи, ключи облаков и репозиториев, учётные данные в URL, номера карт (по Луну, остаются последние
 * 4 цифры). {@link #value} и {@link #details} дополнительно закрывают значения по имени ключа ({@code password},
 * {@code secret}, {@code token}, {@code cookie}…). {@link #email} и {@link #phone} — для показа персональных
 * данных тем, кому полное значение не нужно.
 * <p>
 * Маскирование — вторая линия защиты: модули не должны передавать секреты в журнал и логи вообще.
 */
public final class Masking {

    public static final String MASK = "***";
    /** Длинные значения в журнале обрезаются: деталь — не место для текста заявки. */
    public static final int MAX_VALUE = 1000;

    /** Имя ключа без регистра, «_», «-» и «.»: {@code resetUrl}, {@code reset_url} → {@code reseturl}. */
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            ".*(password|passwd|passphrase|pwd|secret|token|apikey|authorization|cookie|sessionid|privatekey|"
                    + "credential|reseturl|recoverycode).*");
    /** Короткие имена — только целым словом, чтобы не задеть {@code mapping} или {@code shipping}. */
    private static final Pattern SENSITIVE_WORD = Pattern.compile("(?i)(^|.*[_.\\-])(pin|otp|cvv|cvc)($|[_.\\-].*)");

    private static final List<Rule> RULES = List.of(
            // Закрытые ключи целиком
            new Rule(Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(-----END [A-Z ]*PRIVATE KEY-----|$)"),
                    m -> MASK),
            // Учётные данные в URL: scheme://user:password@host
            new Rule(Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/\\s:@]+:[^/\\s@]+@"), m -> m.group(1) + MASK + "@"),
            // Секрет в параметре ссылки
            new Rule(Pattern.compile("(?i)([?&](?:token|access_token|refresh_token|api_key|apikey|key|sig|signature|password|code)=)[^&\\s#]+"),
                    m -> m.group(1) + MASK),
            // Bearer и Basic в заголовке
            new Rule(Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}"), m -> m.group(1) + " " + MASK),
            // JWT
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}"), m -> MASK),
            // Ключи облаков, репозиториев и мессенджеров
            new Rule(Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b"), m -> MASK),
            new Rule(Pattern.compile("\\b(gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|glpat-[A-Za-z0-9_-]{20,}|"
                    + "xox[abprs]-[A-Za-z0-9-]{10,}|sk-[A-Za-z0-9_-]{20,})\\b"), m -> MASK),
            // «пароль: значение», «token=значение»
            new Rule(Pattern.compile("(?iU)\\b(пароль|password|passwd|pwd|pass|secret|секрет|token|токен|api[_-]?key|"
                    + "client[_-]?secret|ключ)(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s&]+)"),
                    m -> m.group(1) + m.group(2) + MASK),
            // «пароль Qwerty123!», «password is S3cret»: значение похоже на секрет (есть цифра или знак)
            new Rule(Pattern.compile("(?iU)\\b(пароль|password|секрет|secret)(\\s+(?:is\\s+|—\\s+|-\\s+)?)"
                    + "((?=\\S*[\\d\\p{Punct}])\\S{6,})"),
                    m -> m.group(1) + m.group(2) + MASK),
            // Номера карт: 13–19 цифр с пробелами или дефисами, проходят проверку Луна
            new Rule(Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b"), Masking::card));

    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    private Masking() {
    }

    /** Секреты в свободном тексте закрыты, остальное без изменений. */
    public static String text(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Rule rule : RULES) {
            out = rule.apply(out);
        }
        return out;
    }

    /** Ключ, значение которого нельзя показывать никогда: пароль, токен, секрет, куки. */
    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(java.util.Locale.ROOT).replaceAll("[_.\\-]", "");
        return SENSITIVE_KEY.matcher(normalized).matches() || SENSITIVE_WORD.matcher(key).matches();
    }

    /** Значение по ключу: секретный ключ — {@value #MASK}, строка — без секретов и не длиннее {@value #MAX_VALUE}. */
    public static Object value(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (isSensitiveKey(key)) {
            return MASK;
        }
        return switch (value) {
            case Number n -> n;
            case Boolean b -> b;
            case Map<?, ?> m -> details(m);
            case Collection<?> c -> c.stream().limit(100).map(v -> value(key, v)).toList();
            default -> truncate(text(String.valueOf(value)));
        };
    }

    /** Детали записи журнала: ключи сохраняются, значения маскируются по {@link #value}. */
    public static Map<String, Object> details(Map<?, ?> details) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (details == null) {
            return out;
        }
        details.forEach((k, v) -> {
            String key = truncate(String.valueOf(k));
            Object masked = value(key, v);
            if (masked != null) {
                out.put(key, masked);
            }
        });
        return out;
    }

    /** {@code ivan.petrov@corp.ru} → {@code i***@corp.ru}. */
    public static String email(String email) {
        if (email == null) {
            return null;
        }
        Matcher m = EMAIL.matcher(email);
        return m.replaceAll(r -> Matcher.quoteReplacement(r.group(1) + MASK + "@" + r.group(2)));
    }

    /** Телефон: видны две последние цифры. */
    public static String phone(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 5) {
            return MASK;
        }
        return MASK + digits.substring(digits.length() - 2);
    }

    /** Для логов приложения: секреты и адреса почты (раздел 12.5: логи без паролей, токенов и персональных данных). */
    public static String forLog(String message) {
        String masked = text(message);
        return masked == null ? null : email(masked);
    }

    private static String truncate(String s) {
        return s.length() <= MAX_VALUE ? s : s.substring(0, MAX_VALUE) + "…";
    }

    private static String card(MatchResult m) {
        String digits = m.group().replaceAll("\\D", "");
        if (!luhn(digits)) {
            return m.group();
        }
        return "****" + digits.substring(digits.length() - 4);
    }

    static boolean luhn(String digits) {
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

    private record Rule(Pattern pattern, Function<MatchResult, String> replacement) {
        String apply(String text) {
            Matcher m = pattern.matcher(text);
            if (!m.find()) {
                return text;
            }
            m.reset();
            return m.replaceAll(r -> Matcher.quoteReplacement(replacement.apply(r)));
        }
    }
}
