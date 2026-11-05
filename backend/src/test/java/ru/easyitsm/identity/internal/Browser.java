package ru.easyitsm.identity.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Браузер для тестов: хранит куки между запросами и, как интерфейс, отправляет CSRF-токен из куки XSRF-TOKEN
 * в заголовке X-XSRF-TOKEN.
 */
class Browser {

    static final String DEMO_TENANT = "demo";

    private final MockMvc mvc;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private boolean sendCsrf = true;

    Browser(MockMvc mvc) {
        this.mvc = mvc;
    }

    Browser withoutCsrf() {
        this.sendCsrf = false;
        return this;
    }

    String cookie(String name) {
        return cookies.get(name);
    }

    void setCookie(String name, String value) {
        cookies.put(name, value);
    }

    void dropCookie(String name) {
        cookies.remove(name);
    }

    String sessionCookieName() {
        return cookies.keySet().stream().filter(k -> k.endsWith("eitsm_session")).findFirst().orElse(null);
    }

    /** Получает CSRF-токен, как это делает экран входа. */
    Browser open() throws Exception {
        get("/api/v1/auth/config");
        return this;
    }

    ResultActions get(String path) throws Exception {
        return perform(request(HttpMethod.GET, path));
    }

    ResultActions post(String path, String json) throws Exception {
        return perform(request(HttpMethod.POST, path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions delete(String path) throws Exception {
        return perform(request(HttpMethod.DELETE, path));
    }

    ResultActions perform(MockHttpServletRequestBuilder builder) throws Exception {
        if (!cookies.isEmpty()) {
            builder.cookie(cookies.entrySet().stream().map(e -> new Cookie(e.getKey(), e.getValue()))
                    .toArray(Cookie[]::new));
        }
        String xsrf = cookies.get("XSRF-TOKEN");
        if (sendCsrf && xsrf != null) {
            builder.header("X-XSRF-TOKEN", xsrf);
        }
        ResultActions result = mvc.perform(builder);
        remember(result.andReturn().getResponse());
        return result;
    }

    private void remember(MockHttpServletResponse response) {
        for (Cookie c : response.getCookies()) {
            update(c.getName(), c.getValue(), c.getMaxAge());
        }
        for (String header : response.getHeaders("Set-Cookie")) {
            String[] parts = header.split(";");
            String[] nv = parts[0].split("=", 2);
            int maxAge = -1;
            for (String p : parts) {
                if (p.trim().toLowerCase().startsWith("max-age=")) {
                    maxAge = Integer.parseInt(p.trim().substring(8));
                }
            }
            update(nv[0].trim(), nv.length > 1 ? nv[1] : "", maxAge);
        }
    }

    private void update(String name, String value, int maxAge) {
        if (maxAge == 0 || value == null || value.isEmpty()) {
            cookies.remove(name);
        } else {
            cookies.put(name, value);
        }
    }
}
