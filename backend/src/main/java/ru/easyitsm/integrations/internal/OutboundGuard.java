package ru.easyitsm.integrations.internal;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Проверка адреса исходящего запроса (раздел 8.5 ТЗ, угроза T-09 SSRF). Вызывается перед каждой отправкой
 * и каждым перенаправлением, после разрешения имени в адреса:
 * <ul>
 *   <li>узел должен совпасть со списком разрешённых адресов арендатора: имя, {@code *.домен} или сеть CIDR;</li>
 *   <li>служебные адреса — метаданные облака и link-local (169.254/16, fe80::/10), 0.0.0.0, multicast — запрещены
 *       всегда;</li>
 *   <li>loopback — только при {@code easyitsm.integrations.outbound.allow-loopback} (тесты и стенд разработчика);</li>
 *   <li>внутренние сети (10/8, 172.16/12, 192.168/16, 100.64/10, fc00::/7) — только если узел разрешён точным
 *       именем или сетью CIDR, но не маской {@code *.домен}: чужой домен не «перенаправит» запрос внутрь.</li>
 * </ul>
 */
final class OutboundGuard {

    /** Адрес запрещён; текст — для журнала доставки и ответа администратору. */
    static final class Denied extends RuntimeException {
        Denied(String message) {
            super(message);
        }
    }

    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final List<String> allowed;
    private final boolean allowLoopback;
    private final Resolver resolver;

    OutboundGuard(List<String> allowed, boolean allowLoopback, Resolver resolver) {
        this.allowed = allowed.stream().map(s -> s.strip().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
        this.allowLoopback = allowLoopback;
        this.resolver = resolver;
    }

    /** @return адреса узла, все проверенные */
    InetAddress[] check(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new Denied("разрешены только http и https");
        }
        if (uri.getRawUserInfo() != null) {
            throw new Denied("логин и пароль в адресе не допускаются");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new Denied("в адресе нет узла");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        Match match = match(host);
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new Denied("узел " + host + " не найден");
        }
        if (addresses.length == 0) {
            throw new Denied("узел " + host + " не найден");
        }
        for (InetAddress a : addresses) {
            checkAddress(host, a, match);
        }
        return addresses;
    }

    private enum Match { EXACT, WILDCARD }

    private Match match(String host) {
        if (allowed.isEmpty()) {
            throw new Denied("список разрешённых адресов вебхуков пуст: добавьте узел в «Интеграции → Вебхуки»");
        }
        for (String rule : allowed) {
            if (rule.contains("/")) {
                continue;
            }
            if (rule.equals(host)) {
                return Match.EXACT;
            }
        }
        for (String rule : allowed) {
            if (rule.startsWith("*.") && host.endsWith(rule.substring(1)) && host.length() > rule.length() - 1) {
                return Match.WILDCARD;
            }
        }
        if (isLiteral(host)) {
            try {
                InetAddress literal = InetAddress.getByName(host);
                if (inAllowedCidr(literal)) {
                    return Match.EXACT;
                }
            } catch (UnknownHostException ignored) {
                // не литерал
            }
        }
        // Имя, которое разрешено сетью CIDR: проверяется по адресам.
        if (allowed.stream().anyMatch(r -> r.contains("/"))) {
            return null;
        }
        throw new Denied("узел " + host + " не входит в разрешённые адреса");
    }

    private void checkAddress(String host, InetAddress a, Match match) {
        if (a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isMulticastAddress() || isMetadata(a)) {
            throw new Denied("служебный адрес " + a.getHostAddress() + " запрещён");
        }
        if (a.isLoopbackAddress()) {
            if (!allowLoopback) {
                throw new Denied("адрес " + a.getHostAddress() + " этого сервера запрещён");
            }
            return;
        }
        boolean cidr = inAllowedCidr(a);
        if (match == null && !cidr) {
            throw new Denied("узел " + host + " не входит в разрешённые адреса");
        }
        if (isPrivate(a) && match != Match.EXACT && !cidr) {
            throw new Denied("внутренний адрес " + a.getHostAddress() + " разрешается только точным именем узла или сетью");
        }
    }

    private boolean inAllowedCidr(InetAddress a) {
        for (String rule : allowed) {
            int slash = rule.indexOf('/');
            if (slash > 0 && Cidr.contains(rule.substring(0, slash), rule.substring(slash + 1), a)) {
                return true;
            }
        }
        return false;
    }

    static boolean isPrivate(InetAddress a) {
        if (a.isSiteLocalAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            // 100.64.0.0/10 — CGNAT, адреса Kubernetes у многих провайдеров
            return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
        }
        if (a instanceof Inet6Address) {
            return (b[0] & 0xfe) == 0xfc;
        }
        return false;
    }

    private static boolean isMetadata(InetAddress a) {
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) {
            // fd00:ec2::254 — метаданные AWS по IPv6
            return a.getHostAddress().startsWith("fd00:ec2:");
        }
        return b.length == 4 && (b[0] & 0xff) == 169 && (b[1] & 0xff) == 254;
    }

    static boolean isLiteral(String host) {
        return host.matches("[0-9.]+") || host.contains(":");
    }

    /** Проверка правила списка: имя, *.домен или сеть CIDR. */
    static boolean validRule(String rule) {
        String r = rule.strip().toLowerCase(Locale.ROOT);
        if (r.contains("/")) {
            int slash = r.indexOf('/');
            return Cidr.valid(r.substring(0, slash), r.substring(slash + 1));
        }
        String name = r.startsWith("*.") ? r.substring(2) : r;
        return name.matches("[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)*")
                || (!r.startsWith("*.") && isLiteral(r) && Cidr.valid(r, null));
    }

    /** Сети CIDR без внешних библиотек. */
    static final class Cidr {

        private Cidr() {
        }

        static boolean valid(String address, String bits) {
            try {
                if (!isLiteral(address)) {
                    return false;
                }
                InetAddress a = InetAddress.getByName(address);
                if (bits == null) {
                    return true;
                }
                int n = Integer.parseInt(bits);
                return n >= 0 && n <= a.getAddress().length * 8;
            } catch (UnknownHostException | NumberFormatException e) {
                return false;
            }
        }

        static boolean contains(String network, String bits, InetAddress a) {
            try {
                if (!isLiteral(network)) {
                    return false;
                }
                byte[] net = InetAddress.getByName(network).getAddress();
                byte[] addr = a.getAddress();
                if (net.length != addr.length) {
                    return false;
                }
                int n = Integer.parseInt(bits);
                for (int i = 0; i < net.length && n > 0; i++, n -= 8) {
                    int mask = n >= 8 ? 0xff : (0xff << (8 - n)) & 0xff;
                    if ((net[i] & mask) != (addr[i] & mask)) {
                        return false;
                    }
                }
                return true;
            } catch (UnknownHostException | NumberFormatException e) {
                return false;
            }
        }
    }
}
