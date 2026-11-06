package ru.easyitsm.mail.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Таблица DNS-записей для тестов SPF, DKIM и DMARC. */
class FakeDns implements DnsResolver {

    final Map<String, List<String>> txt = new HashMap<>();
    final Map<String, List<String>> addresses = new HashMap<>();
    final Map<String, List<String>> mx = new HashMap<>();
    boolean fail;

    FakeDns txt(String name, String... values) {
        txt.put(name, List.of(values));
        return this;
    }

    FakeDns a(String name, String... values) {
        addresses.put(name, List.of(values));
        return this;
    }

    FakeDns mx(String name, String... values) {
        mx.put(name, List.of(values));
        return this;
    }

    @Override
    public List<String> txt(String name) throws DnsException {
        check();
        return txt.getOrDefault(name, List.of());
    }

    @Override
    public List<String> addresses(String name) throws DnsException {
        check();
        return addresses.getOrDefault(name, List.of());
    }

    @Override
    public List<String> mx(String name) throws DnsException {
        check();
        return mx.getOrDefault(name, List.of());
    }

    private void check() throws DnsException {
        if (fail) {
            throw new DnsException("сбой", null);
        }
    }
}
