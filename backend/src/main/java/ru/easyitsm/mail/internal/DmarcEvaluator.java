package ru.easyitsm.mail.internal;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DMARC (RFC 7489): выравнивание доменов SPF и DKIM с доменом From и политика домена отправителя.
 * Организационный домен определяется без списка публичных суффиксов: последние две метки, а для известных
 * доменов второго уровня (co.uk, com.ru, msk.ru и т. п.) — три.
 */
final class DmarcEvaluator {

    private static final Set<String> SECOND_LEVEL = Set.of("co", "com", "net", "org", "gov", "edu", "ac", "msk", "spb",
            "nov", "or", "ne", "go", "mil", "int");

    private final DnsResolver dns;

    DmarcEvaluator(DnsResolver dns) {
        this.dns = dns;
    }

    /**
     * @param result pass, fail, none или temperror
     * @param policy none, quarantine или reject — политика, которую домен просит применить при fail
     */
    record Result(String result, String policy) {
    }

    Result evaluate(String fromDomain, String spfResult, String spfDomain, String dkimResult, List<String> dkimDomains) {
        if (fromDomain == null || fromDomain.isBlank()) {
            return new Result("none", "none");
        }
        String from = fromDomain.toLowerCase(Locale.ROOT);
        Map<String, String> record;
        boolean subdomain = false;
        try {
            record = lookup(from);
            String org = organizational(from);
            if (record == null && !org.equals(from)) {
                record = lookup(org);
                subdomain = true;
            }
        } catch (DnsResolver.DnsException e) {
            return new Result("temperror", "none");
        }
        if (record == null) {
            return new Result("none", "none");
        }
        boolean strictDkim = "s".equalsIgnoreCase(record.get("adkim"));
        boolean strictSpf = "s".equalsIgnoreCase(record.get("aspf"));
        boolean dkimAligned = "pass".equals(dkimResult) && dkimDomains.stream().anyMatch(d -> aligned(d, from, strictDkim));
        boolean spfAligned = "pass".equals(spfResult) && spfDomain != null && aligned(spfDomain, from, strictSpf);
        String policy = subdomain && record.containsKey("sp") ? record.get("sp") : record.getOrDefault("p", "none");
        policy = policy.toLowerCase(Locale.ROOT);
        if (!Set.of("none", "quarantine", "reject").contains(policy)) {
            policy = "none";
        }
        return new Result(dkimAligned || spfAligned ? "pass" : "fail", policy);
    }

    private Map<String, String> lookup(String domain) throws DnsResolver.DnsException {
        for (String txt : dns.txt("_dmarc." + domain)) {
            if (txt.trim().toLowerCase(Locale.ROOT).startsWith("v=dmarc1")) {
                return DkimVerifier.tags(txt);
            }
        }
        return null;
    }

    static boolean aligned(String domain, String from, boolean strict) {
        String d = domain.toLowerCase(Locale.ROOT);
        return strict ? d.equals(from) : organizational(d).equals(organizational(from));
    }

    static String organizational(String domain) {
        String[] labels = domain.toLowerCase(Locale.ROOT).replaceAll("\\.$", "").split("\\.");
        if (labels.length <= 2) {
            return String.join(".", labels);
        }
        int take = labels[labels.length - 1].length() == 2 && SECOND_LEVEL.contains(labels[labels.length - 2]) ? 3 : 2;
        return String.join(".", java.util.Arrays.copyOfRange(labels, labels.length - take, labels.length));
    }
}
