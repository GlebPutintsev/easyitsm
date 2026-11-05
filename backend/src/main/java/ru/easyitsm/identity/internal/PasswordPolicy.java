package ru.easyitsm.identity.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.web.ApiException;

/**
 * Требования к паролю (SEC-04): от 12 символов, не из списков утечек, не совпадает с логином.
 * Список утечек работает без интернета: встроенный список частых паролей плюс файл заказчика,
 * например выгрузка популярных SHA-1 из Have I Been Pwned.
 */
@Component
class PasswordPolicy {

    private static final Logger log = LoggerFactory.getLogger(PasswordPolicy.class);

    private final IdentityProperties.Password props;
    private final Set<String> common = new HashSet<>();
    private final Set<String> breachedSha1 = new HashSet<>();

    PasswordPolicy(IdentityProperties props) throws IOException {
        this.props = props.password();
        var resource = new ClassPathResource("identity/common-passwords.txt");
        try (var reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            reader.lines().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(l -> l.toLowerCase(Locale.ROOT)).forEach(common::add);
        }
        if (this.props.breachedListFile() != null && !this.props.breachedListFile().isBlank()) {
            try (var lines = Files.lines(Path.of(this.props.breachedListFile()), StandardCharsets.UTF_8)) {
                lines.map(String::trim).filter(l -> !l.isEmpty()).forEach(line -> {
                    String value = line.contains(":") ? line.substring(0, line.indexOf(':')) : line;
                    if (value.matches("(?i)[0-9a-f]{40}")) {
                        breachedSha1.add(value.toUpperCase(Locale.ROOT));
                    } else {
                        common.add(value.toLowerCase(Locale.ROOT));
                    }
                });
            }
            log.info("Загружен список утёкших паролей: {} хешей", breachedSha1.size());
        }
    }

    void check(String password, String login, String email) {
        if (password == null || password.length() < props.minLength()) {
            throw ApiException.badRequest("password_too_short",
                    "Пароль должен быть не короче " + props.minLength() + " символов");
        }
        if (password.length() > props.maxLength()) {
            throw ApiException.badRequest("password_too_long", "Слишком длинный пароль");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (login != null && login.length() >= 3 && lower.contains(login.toLowerCase(Locale.ROOT))) {
            throw ApiException.badRequest("password_contains_login", "Пароль не должен содержать логин");
        }
        if (email != null && email.contains("@")) {
            String local = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
            if (local.length() >= 3 && lower.contains(local)) {
                throw ApiException.badRequest("password_contains_login", "Пароль не должен содержать логин");
            }
        }
        if (common.contains(lower) || isRepetitive(password) || breachedSha1.contains(sha1(password))) {
            throw ApiException.badRequest("password_breached",
                    "Этот пароль есть в списках утечек или слишком простой. Выберите другой");
        }
    }

    int minLength() {
        return props.minLength();
    }

    private static boolean isRepetitive(String password) {
        return password.chars().distinct().count() <= 3;
    }

    private static String sha1(String password) {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    java.security.MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
