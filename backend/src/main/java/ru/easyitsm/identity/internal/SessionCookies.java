package ru.easyitsm.identity.internal;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Куки сессии (SEC-06): {@code HttpOnly}, {@code Secure}, {@code SameSite=Lax}, путь «/», без домена.
 * При Secure используется префикс {@code __Host-}: браузер не даст поддомену подменить куку.
 */
@Component
class SessionCookies {

    private final IdentityProperties.Session props;
    private final String sessionName;
    private final String deviceName;

    SessionCookies(IdentityProperties props) {
        this.props = props.session();
        String prefix = this.props.secureCookie() ? "__Host-" : "";
        this.sessionName = prefix + "eitsm_session";
        this.deviceName = prefix + "eitsm_device";
    }

    String sessionName() {
        return sessionName;
    }

    Optional<String> readSession(HttpServletRequest request) {
        return read(request, sessionName);
    }

    Optional<String> readDevice(HttpServletRequest request) {
        return read(request, deviceName);
    }

    void writeSession(HttpServletResponse response, String token, Instant expiresAt) {
        write(response, sessionName, token, Duration.between(Instant.now(), expiresAt));
    }

    void clearSession(HttpServletResponse response) {
        write(response, sessionName, "", Duration.ZERO);
    }

    void writeDevice(HttpServletResponse response, String token, Duration maxAge) {
        write(response, deviceName, token, maxAge);
    }

    private void write(HttpServletResponse response, String name, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(props.secureCookie())
                .sameSite(props.sameSite())
                .path("/")
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()
                    && c.getValue().length() <= 128) {
                return Optional.of(c.getValue());
            }
        }
        return Optional.empty();
    }
}
