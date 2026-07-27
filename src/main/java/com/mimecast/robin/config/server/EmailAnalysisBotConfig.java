package com.mimecast.robin.config.server;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Typed configuration wrapper for the email analysis bot.
 *
 * <p>All settings are read from the bot definition map in {@code bots.json5} and have
 * sensible defaults so any omitted key behaves as if it were enabled.
 */
@SuppressWarnings("unchecked")
public class EmailAnalysisBotConfig {

    private static final List<String> DEFAULT_RBL_PROVIDERS = Arrays.asList(
            "zen.spamhaus.org",
            "bl.spamcop.net",
            "b.barracudacentral.org",
            "dnsbl.sorbs.net");

    private static final List<String> DEFAULT_DBL_PROVIDERS = Arrays.asList(
            "dbl.spamhaus.org",
            "multi.surbl.org",
            "dbl.nordspam.com");

    private static final List<Integer> DEFAULT_PORT_CHECK_PORTS = Arrays.asList(25, 465, 587, 993, 143);

    private final Map<String, Object> map;

    public EmailAnalysisBotConfig(Map<String, Object> map) {
        this.map = map != null ? map : Collections.emptyMap();
    }

    // ── IP DNSBL ──────────────────────────────────────────────────────────────

    public boolean isRblCheckEnabled() {
        return bool("rblCheckEnabled", true);
    }

    public List<String> getRblProviders() {
        return stringList("rblProviders", DEFAULT_RBL_PROVIDERS);
    }

    public int getRblTimeoutSeconds() {
        return num("rblTimeoutSeconds", 5);
    }

    // ── Domain Blocklist (DBL / SURBL / URIBL) ───────────────────────────────

    public boolean isDblCheckEnabled() {
        return bool("dblCheckEnabled", true);
    }

    public List<String> getDblProviders() {
        return stringList("dblProviders", DEFAULT_DBL_PROVIDERS);
    }

    public int getDblTimeoutSeconds() {
        return num("dblTimeoutSeconds", 5);
    }

    // ── Port / TLS ───────────────────────────────────────────────────────────

    public boolean isPortCheckEnabled() {
        return bool("portCheckEnabled", true);
    }

    public List<Integer> getPortCheckPorts() {
        if (map.containsKey("portCheckPorts")) {
            List<?> raw = (List<?>) map.get("portCheckPorts");
            if (raw != null && !raw.isEmpty()) {
                List<Integer> ports = new java.util.ArrayList<>();
                for (Object v : raw) {
                    if (v instanceof Number) ports.add(((Number) v).intValue());
                }
                return ports;
            }
        }
        return DEFAULT_PORT_CHECK_PORTS;
    }

    public int getPortCheckTimeoutSeconds() {
        return num("portCheckTimeoutSeconds", 10);
    }

    // ── rDNS / FCrDNS ────────────────────────────────────────────────────────

    public boolean isRdnsCheckEnabled() {
        return bool("rdnsCheckEnabled", true);
    }

    // ── SPF / DKIM / DMARC ───────────────────────────────────────────────────

    public boolean isSpfCheckEnabled() {
        return bool("spfCheckEnabled", true);
    }

    public boolean isDkimCheckEnabled() {
        return bool("dkimCheckEnabled", true);
    }

    public boolean isDmarcCheckEnabled() {
        return bool("dmarcCheckEnabled", true);
    }

    // ── MX Records ───────────────────────────────────────────────────────────

    public boolean isMxCheckEnabled() {
        return bool("mxCheckEnabled", true);
    }

    // ── MTA-STS ──────────────────────────────────────────────────────────────

    public boolean isMtaStsCheckEnabled() {
        return bool("mtaStsCheckEnabled", true);
    }

    /**
     * Override domain for MTA-STS and DANE checks.
     * When null, falls back to the first resolved MX hostname for the sender domain.
     * Set this to the receiving domain (e.g. "mail.inboxment.com") to check your
     * inbound infrastructure rather than the sender's sending subdomain.
     */
    public String getMtaStsTargetDomain() {
        Object v = map.get("mtaStsTargetDomain");
        if (v instanceof String && !((String) v).isEmpty()) return (String) v;
        return null;
    }

    // ── DANE ─────────────────────────────────────────────────────────────────

    public boolean isDaneCheckEnabled() {
        return bool("daneCheckEnabled", true);
    }

    // ── Spam / Rspamd Analysis ───────────────────────────────────────────────

    public boolean isSpamAnalysisEnabled() {
        return bool("spamAnalysisEnabled", true);
    }

    // ── Pass / Fail Verdict Summary ──────────────────────────────────────────

    public boolean isVerdictEnabled() {
        return bool("verdictEnabled", true);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean bool(String key, boolean def) {
        Object v = map.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        return def;
    }

    private int num(String key, int def) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return def;
    }

    private List<String> stringList(String key, List<String> def) {
        Object v = map.get(key);
        if (v instanceof List && !((List<?>) v).isEmpty()) {
            List<String> result = new java.util.ArrayList<>();
            for (Object item : (List<?>) v) {
                if (item instanceof String) result.add((String) item);
            }
            if (!result.isEmpty()) return result;
        }
        return def;
    }
}
