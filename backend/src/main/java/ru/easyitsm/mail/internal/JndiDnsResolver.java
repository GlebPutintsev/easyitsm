package ru.easyitsm.mail.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.List;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import org.springframework.stereotype.Component;

/** DNS через JNDI из JDK: системные серверы имён, таймаут 3 с, без сторонних библиотек. */
@Component
class JndiDnsResolver implements DnsResolver {

    @Override
    public List<String> txt(String name) throws DnsException {
        List<String> out = new ArrayList<>();
        for (Object v : lookup(name, "TXT")) {
            out.add(joinTxt(v.toString()));
        }
        return out;
    }

    @Override
    public List<String> addresses(String name) throws DnsException {
        List<String> out = new ArrayList<>();
        for (Object v : lookup(name, "A")) {
            out.add(v.toString().trim());
        }
        for (Object v : lookup(name, "AAAA")) {
            out.add(v.toString().trim());
        }
        return out;
    }

    @Override
    public List<String> mx(String name) throws DnsException {
        record Mx(int pref, String host) {
        }
        List<Mx> list = new ArrayList<>();
        for (Object v : lookup(name, "MX")) {
            String[] parts = v.toString().trim().split("\\s+");
            if (parts.length == 2) {
                list.add(new Mx(Integer.parseInt(parts[0]), parts[1].replaceAll("\\.$", "")));
            }
        }
        return list.stream().sorted(Comparator.comparingInt(Mx::pref)).map(Mx::host).toList();
    }

    private List<Object> lookup(String name, String type) throws DnsException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "3000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes("dns:/" + name, new String[] {type});
            Attribute attr = attrs.get(type);
            List<Object> out = new ArrayList<>();
            if (attr != null) {
                NamingEnumeration<?> e = attr.getAll();
                while (e.hasMore()) {
                    out.add(e.next());
                }
            }
            return out;
        } catch (NameNotFoundException e) {
            return List.of();
        } catch (NamingException e) {
            if (e.getMessage() != null && e.getMessage().contains("DNS name not found")) {
                return List.of();
            }
            throw new DnsException("DNS " + type + " " + name + ": " + e.getMessage(), e);
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (NamingException ignored) {
                    // закрытие контекста не влияет на результат
                }
            }
        }
    }

    /** JNDI отдаёт TXT как {@code "часть1" "часть2"}: склеиваем части без кавычек. */
    static String joinTxt(String raw) {
        String s = raw.trim();
        if (!s.startsWith("\"")) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                in = !in;
            } else if (c == '\\' && in && i + 1 < s.length()) {
                sb.append(s.charAt(++i));
            } else if (in) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
