package ru.easyitsm.mail.internal;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Проверка SPF (RFC 7208) для входящего SMTP, где известен адрес клиента. Поддержаны механизмы all, include,
 * a, mx, ip4, ip6, exists, модификатор redirect и простые макросы %{s} %{l} %{o} %{d} %{i} %{h}.
 * Механизм ptr не используется (RFC 7208 не рекомендует) и не совпадает. Предел — 10 DNS-запросов.
 */
final class SpfChecker {

    private static final int MAX_LOOKUPS = 10;

    private final DnsResolver dns;
    private int lookups;

    SpfChecker(DnsResolver dns) {
        this.dns = dns;
    }

    /** @return pass, fail, softfail, neutral, none, temperror или permerror */
    String check(String clientIp, String domain, String sender, String helo) {
        if (clientIp == null || domain == null || domain.isBlank()) {
            return "none";
        }
        lookups = 0;
        try {
            return checkHost(InetAddress.getByName(clientIp), domain.toLowerCase(Locale.ROOT), sender, helo, 0);
        } catch (UnknownHostException e) {
            return "permerror";
        }
    }

    private String checkHost(InetAddress ip, String domain, String sender, String helo, int depth) {
        if (depth > 10) {
            return "permerror";
        }
        List<String> records;
        try {
            records = dns.txt(domain).stream().filter(r -> r.toLowerCase(Locale.ROOT).matches("v=spf1(\\s.*)?")).toList();
        } catch (DnsResolver.DnsException e) {
            return "temperror";
        }
        if (records.isEmpty()) {
            return "none";
        }
        if (records.size() > 1) {
            return "permerror";
        }
        String redirect = null;
        String[] terms = records.getFirst().trim().split("\\s+");
        try {
            for (int i = 1; i < terms.length; i++) {
                String term = terms[i];
                if (term.isEmpty()) {
                    continue;
                }
                String lower = term.toLowerCase(Locale.ROOT);
                if (lower.startsWith("redirect=")) {
                    redirect = expand(term.substring(9), ip, domain, sender, helo);
                    continue;
                }
                if (lower.startsWith("exp=") || lower.matches("[a-z][a-z0-9_.-]*=.*")) {
                    continue;
                }
                char q = term.charAt(0);
                String qualifier = "+";
                if (q == '+' || q == '-' || q == '~' || q == '?') {
                    qualifier = String.valueOf(q);
                    term = term.substring(1);
                    lower = lower.substring(1);
                }
                Boolean match = matches(term, lower, ip, domain, sender, helo, depth);
                if (match == null) {
                    return "temperror";
                }
                if (match) {
                    return switch (qualifier) {
                        case "-" -> "fail";
                        case "~" -> "softfail";
                        case "?" -> "neutral";
                        default -> "pass";
                    };
                }
            }
            if (redirect != null) {
                if (++lookups > MAX_LOOKUPS) {
                    return "permerror";
                }
                String r = checkHost(ip, redirect.toLowerCase(Locale.ROOT), sender, helo, depth + 1);
                return "none".equals(r) ? "permerror" : r;
            }
            return "neutral";
        } catch (PermError e) {
            return "permerror";
        }
    }

    /** null — временная ошибка DNS. */
    private Boolean matches(String term, String lower, InetAddress ip, String domain, String sender, String helo,
                            int depth) {
        try {
            if (lower.equals("all")) {
                return true;
            }
            if (lower.startsWith("include:")) {
                countLookup();
                String r = checkHost(ip, expand(term.substring(8), ip, domain, sender, helo).toLowerCase(Locale.ROOT),
                        sender, helo, depth + 1);
                return switch (r) {
                    case "pass" -> true;
                    case "fail", "softfail", "neutral" -> false;
                    case "temperror" -> null;
                    default -> throw new PermError();
                };
            }
            if (lower.startsWith("ip4:") || lower.startsWith("ip6:")) {
                return inCidr(ip, term.substring(4));
            }
            if (lower.equals("a") || lower.startsWith("a:") || lower.startsWith("a/")) {
                countLookup();
                Target t = target(term.substring(1), ip, domain, sender, helo);
                for (String addr : dns.addresses(t.host)) {
                    if (inCidr(ip, addr + t.cidr(ip))) {
                        return true;
                    }
                }
                return false;
            }
            if (lower.equals("mx") || lower.startsWith("mx:") || lower.startsWith("mx/")) {
                countLookup();
                Target t = target(term.substring(2), ip, domain, sender, helo);
                List<String> hosts = dns.mx(t.host);
                for (String host : hosts.subList(0, Math.min(10, hosts.size()))) {
                    for (String addr : dns.addresses(host)) {
                        if (inCidr(ip, addr + t.cidr(ip))) {
                            return true;
                        }
                    }
                }
                return false;
            }
            if (lower.startsWith("exists:")) {
                countLookup();
                return !dns.addresses(expand(term.substring(7), ip, domain, sender, helo)).isEmpty();
            }
            if (lower.equals("ptr") || lower.startsWith("ptr:")) {
                countLookup();
                return false;
            }
            throw new PermError();
        } catch (DnsResolver.DnsException e) {
            return null;
        }
    }

    private void countLookup() {
        if (++lookups > MAX_LOOKUPS) {
            throw new PermError();
        }
    }

    private record Target(String host, String cidr4, String cidr6) {
        String cidr(InetAddress ip) {
            String c = ip.getAddress().length == 4 ? cidr4 : cidr6;
            return c == null ? "" : "/" + c;
        }
    }

    /** {@code [:домен][/cidr4][//cidr6]} после имени механизма. */
    private Target target(String rest, InetAddress ip, String domain, String sender, String helo) {
        String host = domain;
        String cidr4 = null;
        String cidr6 = null;
        if (rest.startsWith(":")) {
            rest = rest.substring(1);
            int slash = rest.indexOf('/');
            host = expand(slash < 0 ? rest : rest.substring(0, slash), ip, domain, sender, helo);
            rest = slash < 0 ? "" : rest.substring(slash);
        }
        if (rest.startsWith("//")) {
            cidr6 = rest.substring(2);
        } else if (rest.startsWith("/")) {
            String[] parts = rest.substring(1).split("//");
            cidr4 = parts[0];
            cidr6 = parts.length > 1 ? parts[1] : null;
        }
        return new Target(host, cidr4, cidr6);
    }

    static boolean inCidr(InetAddress ip, String spec) {
        try {
            String[] parts = spec.split("/");
            InetAddress net = InetAddress.getByName(parts[0]);
            byte[] a = ip.getAddress();
            byte[] b = net.getAddress();
            if (a.length != b.length) {
                return false;
            }
            int bits = parts.length > 1 ? Integer.parseInt(parts[1]) : a.length * 8;
            if (bits < 0 || bits > a.length * 8) {
                throw new PermError();
            }
            BigInteger mask = bits == 0 ? BigInteger.ZERO
                    : BigInteger.ONE.shiftLeft(a.length * 8).subtract(BigInteger.ONE)
                            .xor(BigInteger.ONE.shiftLeft(a.length * 8 - bits).subtract(BigInteger.ONE));
            return new BigInteger(1, a).and(mask).equals(new BigInteger(1, b).and(mask));
        } catch (UnknownHostException | NumberFormatException e) {
            throw new PermError();
        }
    }

    private static String expand(String spec, InetAddress ip, String domain, String sender, String helo) {
        if (!spec.contains("%")) {
            return spec;
        }
        String s = sender == null || sender.isBlank() ? "postmaster@" + domain : sender;
        int at = s.lastIndexOf('@');
        String local = at < 0 ? "postmaster" : s.substring(0, at);
        String senderDomain = at < 0 ? s : s.substring(at + 1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (c != '%') {
                out.append(c);
                continue;
            }
            if (i + 1 >= spec.length()) {
                throw new PermError();
            }
            char n = spec.charAt(++i);
            switch (n) {
                case '%' -> out.append('%');
                case '_' -> out.append(' ');
                case '-' -> out.append("%20");
                case '{' -> {
                    int end = spec.indexOf('}', i);
                    if (end < 0) {
                        throw new PermError();
                    }
                    String macro = spec.substring(i + 1, end).toLowerCase(Locale.ROOT);
                    i = end;
                    out.append(switch (macro) {
                        case "s" -> s;
                        case "l" -> local;
                        case "o" -> senderDomain;
                        case "d" -> domain;
                        case "i" -> ip.getHostAddress();
                        case "h" -> helo == null ? domain : helo;
                        default -> throw new PermError();
                    });
                }
                default -> throw new PermError();
            }
        }
        return out.toString();
    }

    private static final class PermError extends RuntimeException {
        PermError() {
            super(null, null, false, false);
        }
    }
}
