package com.mimecast.robin.scanners;

import com.mimecast.robin.mx.assets.DnsRecord;
import com.mimecast.robin.mx.client.XBillDnsRecordClient;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds the trace headers Robin adds to inbound messages: an RFC 8601
 * {@code Authentication-Results} header and SpamAssassin-style {@code X-Spam-*} headers.
 *
 * <p>SPF, DKIM, DMARC and ARC verdicts come from the Rspamd scan result; a check Rspamd did not
 * report is left out. Robin adds {@code auth} for authenticated sessions and {@code iprev} for
 * clients with a public address, so the header is present even when Rspamd is disabled.
 */
public final class AuthenticationHeaders {

    /**
     * Forward lookup used to confirm a client's reverse DNS name.
     */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * Forward lookup through Robin's DNS client and resolver configuration.
     */
    public static final Resolver DNS = host -> {
        List<DnsRecord> records = DnsLookup.CLIENT.getARecords(host).orElseThrow(() -> new UnknownHostException(host));
        List<InetAddress> addresses = new ArrayList<>();
        for (DnsRecord record : records) {
            if (record.getValue() != null) addresses.add(InetAddress.getByName(record.getValue()));
        }
        return addresses.toArray(new InetAddress[0]);
    };

    private static final class DnsLookup {
        private static final XBillDnsRecordClient CLIENT = new XBillDnsRecordClient();
    }

    /**
     * Rspamd symbol to RFC 8601 result, per method, in the order symbols are looked up.
     */
    private static final Map<String, String> SPF = ordered(
            "R_SPF_ALLOW", "pass", "R_SPF_FAIL", "fail", "R_SPF_SOFTFAIL", "softfail",
            "R_SPF_NEUTRAL", "neutral", "R_SPF_DNSFAIL", "temperror", "R_SPF_PERMFAIL", "permerror",
            "R_SPF_NA", "none");
    private static final Map<String, String> DKIM = ordered(
            "R_DKIM_ALLOW", "pass", "R_DKIM_REJECT", "fail", "R_DKIM_TEMPFAIL", "temperror",
            "R_DKIM_PERMFAIL", "permerror", "R_DKIM_NA", "none");
    private static final Map<String, String> DMARC = ordered(
            "DMARC_POLICY_ALLOW", "pass", "DMARC_POLICY_ALLOW_WITH_FAILURES", "pass",
            "DMARC_POLICY_REJECT", "fail", "DMARC_POLICY_QUARANTINE", "fail", "DMARC_POLICY_SOFTFAIL", "fail",
            "DMARC_DNSFAIL", "temperror", "DMARC_BAD_POLICY", "permerror", "DMARC_NA", "none");
    private static final Map<String, String> ARC = ordered(
            "ARC_ALLOW", "pass", "ARC_REJECT", "fail", "ARC_INVALID", "fail", "ARC_DNSFAIL", "temperror",
            "ARC_NA", "none");

    private static final Pattern DOMAIN = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}\\.)+[A-Za-z0-9-]{1,63}");
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+\\-./0-9=?A-Z^_`a-z{|}~@]+");

    private AuthenticationHeaders() {
    }

    /**
     * Builds the Authentication-Results value.
     *
     * @param authservId Robin's authentication service identifier, normally its hostname.
     * @param session    Session the message arrived on.
     * @param envelope   Envelope of the message.
     * @param rspamd     Rspamd scan result, or null when the message was not scanned.
     * @param resolver   Forward resolver for the iprev check.
     * @return Header value, without the field name.
     */
    public static String authenticationResults(String authservId, Session session, MessageEnvelope envelope,
                                               Map<String, Object> rspamd, Resolver resolver) {
        List<String> results = new ArrayList<>();
        String iprev = iprev(session, resolver);
        if (iprev != null) results.add(iprev);

        Map<?, ?> symbols = rspamd != null && rspamd.get("symbols") instanceof Map<?, ?> map ? map : Map.of();
        String spf = verdict(symbols, SPF);
        if (spf != null) {
            // A null sender is checked by its HELO identity (RFC 7208 2.4).
            String sender = envelope.getMail();
            String property = sender == null || sender.isEmpty()
                    ? (token(helo(session)) ? " smtp.helo=" + helo(session) : "")
                    : (token(sender) ? " smtp.mailfrom=" + sender : "");
            results.add("spf=" + spf + property);
        }
        String dkimSymbol = symbol(symbols, DKIM);
        if (dkimSymbol != null) {
            String domain = optionDomain(symbols.get(dkimSymbol));
            results.add("dkim=" + DKIM.get(dkimSymbol) + (domain != null ? " header.d=" + domain : ""));
        }
        String dmarcSymbol = symbol(symbols, DMARC);
        if (dmarcSymbol != null) {
            String domain = optionDomain(symbols.get(dmarcSymbol));
            results.add("dmarc=" + DMARC.get(dmarcSymbol) + (domain != null ? " header.from=" + domain : ""));
        }
        String arc = verdict(symbols, ARC);
        if (arc != null) results.add("arc=" + arc);

        if (session.isAuth()) {
            String user = session.getUsername();
            results.add("auth=pass" + (token(user) ? " smtp.auth=" + user : ""));
        }

        return authservId + ";" + (results.isEmpty() ? " none" : "\r\n\t" + String.join(";\r\n\t", results));
    }

    /**
     * Builds the X-Spam-* headers from an Rspamd scan result.
     *
     * <p>A message is spam when Rspamd asked for it to be marked (add header or rewrite subject) or
     * its score reached the add header threshold. Rejected messages never get this far.
     *
     * @param rspamd Rspamd scan result, or null when the message was not scanned.
     * @return Name and value pairs, in order; empty when there is no usable score.
     */
    public static List<String[]> spamHeaders(Map<String, Object> rspamd) {
        if (rspamd == null || !(rspamd.get("score") instanceof Number score) || !Double.isFinite(score.doubleValue())) {
            return List.of();
        }
        double required = threshold(rspamd);
        String action = rspamd.get("action") instanceof String value ? value.toLowerCase(Locale.ROOT) : "";
        boolean spam = action.equals("add header") || action.equals("rewrite subject")
                || (Double.isFinite(required) && score.doubleValue() >= required);
        String formattedScore = format(score.doubleValue());
        String status = (spam ? "Yes" : "No") + ", score=" + formattedScore
                + (Double.isFinite(required) ? " required=" + format(required) : "")
                + (action.isEmpty() ? "" : " action=\"" + action + "\"");
        List<String[]> headers = new ArrayList<>();
        headers.add(new String[]{"X-Spam-Status", status});
        headers.add(new String[]{"X-Spam-Score", formattedScore});
        if (spam) headers.add(new String[]{"X-Spam-Flag", "YES"});
        return headers;
    }

    /**
     * Whether an existing Authentication-Results value claims the given authserv-id (RFC 8601 section 5).
     *
     * @param value      Header value, possibly folded.
     * @param authservId Our authserv-id.
     * @return Boolean.
     */
    public static boolean claims(String value, String authservId) {
        String unfolded = value.replaceAll("\\([^)]*\\)", " ").replaceAll("[\\r\\n\\t ]+", " ").trim();
        int end = 0;
        while (end < unfolded.length() && unfolded.charAt(end) != ';' && unfolded.charAt(end) != ' ') end++;
        return unfolded.substring(0, end).equalsIgnoreCase(authservId);
    }

    private static String iprev(Session session, Resolver resolver) {
        String address = session.getFriendAddr();
        if (address == null || address.isBlank()) return null;
        InetAddress client;
        try {
            client = InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            return null;
        }
        // Internal clients have no meaningful reverse DNS; Robin does not look them up.
        if (client.isLoopbackAddress() || client.isSiteLocalAddress() || client.isLinkLocalAddress()
                || client.isAnyLocalAddress()) {
            return null;
        }
        String policy = " policy.iprev=" + address;
        String name = session.getFriendRdns();
        if (name == null || name.isBlank() || name.equals(address) || !DOMAIN.matcher(name).matches()) {
            return "iprev=permerror" + policy;
        }
        try {
            for (InetAddress forward : resolver.resolve(name)) {
                if (forward.equals(client)) return "iprev=pass" + policy + " (" + name + ")";
            }
            return "iprev=fail" + policy + " (" + name + ")";
        } catch (UnknownHostException e) {
            return "iprev=fail" + policy + " (" + name + ")";
        } catch (RuntimeException e) {
            return "iprev=temperror" + policy;
        }
    }

    private static String helo(Session session) {
        String helo = session.getEhlo() != null && !session.getEhlo().isBlank() ? session.getEhlo() : session.getHelo();
        return helo == null ? "" : helo;
    }

    private static String symbol(Map<?, ?> symbols, Map<String, String> mapping) {
        for (String name : mapping.keySet()) {
            if (symbols.containsKey(name)) return name;
        }
        return null;
    }

    private static String verdict(Map<?, ?> symbols, Map<String, String> mapping) {
        String name = symbol(symbols, mapping);
        return name == null ? null : mapping.get(name);
    }

    /**
     * The domain Rspamd names in a symbol's options, such as {@code example.com:s=selector}.
     */
    private static String optionDomain(Object symbol) {
        if (!(symbol instanceof Map<?, ?> details) || !(details.get("options") instanceof List<?> options)) return null;
        for (Object option : options) {
            String text = String.valueOf(option);
            int colon = text.indexOf(':');
            String domain = (colon > 0 ? text.substring(0, colon) : text).trim().toLowerCase(Locale.ROOT);
            if (DOMAIN.matcher(domain).matches()) return domain;
        }
        return null;
    }

    private static double threshold(Map<String, Object> rspamd) {
        if (rspamd.get("thresholds") instanceof Map<?, ?> thresholds
                && thresholds.get("add header") instanceof Number addHeader) {
            return addHeader.doubleValue();
        }
        return rspamd.get("required_score") instanceof Number required ? required.doubleValue() : Double.NaN;
    }

    private static boolean token(String value) {
        return value != null && !value.isEmpty() && value.length() <= 255 && TOKEN.matcher(value).matches();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return java.util.Collections.unmodifiableMap(map);
    }
}
