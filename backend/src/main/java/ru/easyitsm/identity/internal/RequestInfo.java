package ru.easyitsm.identity.internal;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Адрес и браузер клиента для журнала входов. Адрес берётся из {@code getRemoteAddr()}: за прокси его
 * подставляет Tomcat по {@code server.forward-headers-strategy}, доверять {@code X-Forwarded-For} напрямую нельзя.
 */
record RequestInfo(String ip, String userAgent) {

    static RequestInfo of(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        if (ua != null && ua.length() > 512) {
            ua = ua.substring(0, 512);
        }
        return new RequestInfo(request.getRemoteAddr(), ua);
    }
}
