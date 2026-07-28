package com.mimecast.robin.bots;

import com.google.gson.Gson;
import com.mimecast.robin.config.server.BotConfig;
import com.mimecast.robin.config.server.EmailAnalysisBotConfig;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.mime.parts.MimePart;
import com.mimecast.robin.mime.parts.TextMimePart;
import com.mimecast.robin.mx.MXResolver;
import com.mimecast.robin.mx.StrictMx;
import com.mimecast.robin.mx.assets.DnsRecord;
import com.mimecast.robin.mx.assets.StsReport;
import com.mimecast.robin.mx.client.XBillDnsRecordClient;
import com.mimecast.robin.mx.dane.DaneChecker;
import com.mimecast.robin.mx.dane.DaneRecord;
import com.mimecast.robin.scanners.port.PortTlsChecker;
import com.mimecast.robin.scanners.port.PortTlsResult;
import com.mimecast.robin.scanners.rbl.DblChecker;
import com.mimecast.robin.scanners.rbl.DblResult;
import com.mimecast.robin.scanners.rbl.RblChecker;
import com.mimecast.robin.scanners.rbl.RblResult;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xbill.DNS.Address;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Message-centric email deliverability and security analysis bot.
 */
public class EmailAnalysisBot implements BotProcessor {
    private static final Logger log = LogManager.getLogger(EmailAnalysisBot.class);

    private static final Pattern SIMPLE_EMAIL = Pattern.compile("(?i)<?([^\\s<>@]+@[^\\s<>@]+)>?");
    private static final Pattern DKIM_TAG = Pattern.compile("(?i)(^|;)\\s*([a-z])\\s*=\\s*([^;]+)");
    private static final Pattern DOMAIN_LABEL = Pattern.compile("(?i)^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Gson GSON = new Gson();

    @Override
    public void process(Connection connection, EmailParser emailParser,
                        String botAddress, BotConfig.BotDefinition botDefinition) {
        try {
            log.info("Processing email analysis bot for: {} session: {}",
                    botAddress, connection.getSession().getUID());

            String replyTo = BotReplyAddressResolver.resolveReplyAddress(connection, botAddress);
            if (replyTo == null || replyTo.isEmpty()) {
                log.warn("Cannot determine reply address for bot request from session: {}",
                        connection.getSession().getUID());
                return;
            }

            EmailAnalysisBotConfig cfg = new EmailAnalysisBotConfig(
                    botDefinition != null ? botDefinition.getMap() : null);

            AnalysisReport report = analyze(connection, emailParser, cfg);
            queueResponse(connection.getSession(), botAddress, replyTo, report);

            log.info("Queued analysis report to: {} session: {}",
                    replyTo, connection.getSession().getUID());
        } catch (Exception e) {
            log.error("Error in email analysis bot for: {} session: {}",
                    botAddress, connection.getSession().getUID(), e);
        }
    }

    AnalysisReport analyze(Connection connection, EmailParser emailParser, EmailAnalysisBotConfig cfg) {
        Session session = connection.getSession();
        MessageEnvelope envelope = currentEnvelope(session);
        MessageContext ctx = MessageContext.from(session, envelope, emailParser);
        AnalysisReport report = new AnalysisReport(session.getUID(), LocalDateTime.now(), ctx);

        if (cfg.isRdnsCheckEnabled()) {
            report.add(checkSendingHostIdentity(ctx));
        }
        if (cfg.isRblCheckEnabled()) {
            report.add(checkRbl(ctx, cfg));
        }
        if (cfg.isDblCheckEnabled()) {
            report.addAll(checkDbl(ctx, cfg));
        }
        if (cfg.isDomainAgeCheckEnabled()) {
            for (String domain : ctx.mailDomains()) {
                report.add(checkDomainAge(domain, cfg));
            }
        }
        if (cfg.isSpfCheckEnabled()) {
            report.add(checkSpf(ctx));
        }
        if (cfg.isDkimCheckEnabled()) {
            report.add(checkDkim(ctx));
            report.addAll(checkDkimSelectors(ctx));
        }
        if (cfg.isDmarcCheckEnabled()) {
            report.add(checkDmarc(ctx));
            report.add(checkDmarcRecord(ctx));
        }
        if (cfg.isMxCheckEnabled()) {
            for (String domain : ctx.mailDomains()) {
                report.addAll(checkMxDomain(domain, ctx, cfg));
            }
        }
        if (cfg.isPortCheckEnabled()) {
            report.add(checkSendingHostPorts(ctx, cfg));
        }
        if (cfg.isMtaStsCheckEnabled()) {
            for (String domain : ctx.mailDomains()) {
                report.add(checkMtaSts(domain));
                report.add(checkTlsRpt(domain));
            }
        }
        if (cfg.isDaneCheckEnabled()) {
            for (String domain : ctx.mailDomains()) {
                report.add(checkDane(domain));
            }
        }
        if (cfg.isSpamAnalysisEnabled()) {
            report.add(checkSpam(ctx));
            report.add(checkMessageHeaders(ctx));
            report.add(checkListUnsubscribe(ctx));
        }

        return report;
    }

    private CheckResult checkSendingHostIdentity(MessageContext ctx) {
        CheckResult.Builder b = CheckResult.builder(Category.SENDING_HOST, "EHLO / PTR identity")
                .reference("RFC 5321 4.1.4")
                .evidence("Connecting IP", nvl(ctx.remoteIp(), "N/A"))
                .evidence("EHLO/HELO", nvl(ctx.ehlo(), "N/A"))
                .evidence("PTR", nvl(ctx.rdns(), "N/A"));

        if (isBlank(ctx.remoteIp())) {
            return b.status(Status.SKIPPED)
                    .summary("No connecting IP was available.")
                    .remediation("Capture the SMTP peer address before bot processing.")
                    .build();
        }
        if (isBlank(ctx.ehlo())) {
            return b.status(Status.WARN)
                    .summary("No EHLO/HELO value was recorded.")
                    .remediation("A sending MTA should identify itself with EHLO using its primary host name.")
                    .build();
        }

        String ehlo = trimDot(ctx.ehlo());
        boolean ehloLiteral = ehlo.startsWith("[") && ehlo.endsWith("]");
        if (!ehloLiteral && !isValidSmtpDomain(ehlo)) {
            return b.status(Status.FAIL)
                    .summary("EHLO/HELO is not a valid SMTP domain name.")
                    .remediation("Configure the sender to use a resolvable FQDN or a valid address literal.")
                    .build();
        }

        Set<String> ehloIps = resolveHostAddresses(ehloLiteral ? literalValue(ehlo) : ehlo);
        Set<String> rdnsIps = resolveHostAddresses(trimDot(ctx.rdns()));
        boolean ehloResolvesToIp = ehloIps.contains(ctx.remoteIp());
        boolean ptrForwardConfirmed = rdnsIps.contains(ctx.remoteIp());
        boolean ehloMatchesPtr = !isBlank(ctx.rdns()) && trimDot(ctx.rdns()).equalsIgnoreCase(ehlo);

        b.evidence("EHLO forward IPs", formatSet(ehloIps))
                .evidence("PTR forward IPs", formatSet(rdnsIps))
                .evidence("EHLO resolves to connecting IP", yesNo(ehloResolvesToIp))
                .evidence("PTR forward-confirmed", yesNo(ptrForwardConfirmed))
                .evidence("EHLO matches PTR", yesNo(ehloMatchesPtr));

        if (ehloLiteral) {
            Status status = ehloResolvesToIp ? Status.WARN : Status.FAIL;
            return b.status(status)
                    .summary(ehloResolvesToIp ?
                            "EHLO uses an address literal that matches the connecting IP." :
                            "EHLO address literal does not match the connecting IP.")
                    .remediation("Use a stable FQDN with matching forward and reverse DNS for best deliverability.")
                    .build();
        }
        if (ehloMatchesPtr && ptrForwardConfirmed && ehloResolvesToIp) {
            return b.status(Status.PASS)
                    .summary("EHLO, PTR and forward DNS are consistent.")
                    .build();
        }
        if (ehloResolvesToIp && ptrForwardConfirmed) {
            return b.status(Status.WARN)
                    .summary("EHLO and PTR both resolve to the connecting IP, but the hostnames differ.")
                    .remediation("Use the PTR hostname as EHLO, or align PTR and EHLO naming.")
                    .build();
        }
        return b.status(Status.WARN)
                .summary("EHLO/PTR identity is not fully aligned.")
                .remediation("Configure PTR, forward DNS and EHLO so the sending host can be traced consistently.")
                .build();
    }

    private CheckResult checkRbl(MessageContext ctx, EmailAnalysisBotConfig cfg) {
        CheckResult.Builder b = CheckResult.builder(Category.REPUTATION, "Sender IP DNSBL")
                .reference("DNSBL operational reputation check")
                .evidence("Connecting IP", nvl(ctx.remoteIp(), "N/A"));
        if (isBlank(ctx.remoteIp())) {
            return b.status(Status.SKIPPED).summary("No connecting IP was available.").build();
        }
        List<RblResult> results = RblChecker.checkIpAgainstRbls(ctx.remoteIp(), cfg.getRblProviders(),
                cfg.getRblTimeoutSeconds());
        boolean listed = results.stream().anyMatch(RblResult::isListed);
        for (RblResult result : results) {
            b.evidence(result.getRblProvider(),
                    result.isListed() ? "LISTED " + result.getResponseRecords() : "clear");
        }
        return b.status(listed ? Status.FAIL : Status.PASS)
                .summary(listed ? "The connecting IP is listed by at least one DNSBL." :
                        "The connecting IP was not listed by configured DNSBLs.")
                .remediation(listed ? "Review the listed DNSBL result and remediate reputation or abuse issues." : null)
                .build();
    }

    private List<CheckResult> checkDbl(MessageContext ctx, EmailAnalysisBotConfig cfg) {
        List<CheckResult> checks = new ArrayList<>();
        for (String domain : ctx.reputationDomains()) {
            CheckResult.Builder b = CheckResult.builder(Category.REPUTATION, "Domain reputation: " + domain)
                    .reference("DBL/SURBL operational reputation check")
                    .evidence("Domain", domain);
            List<DblResult> results = DblChecker.checkDomainAgainstDbls(domain, cfg.getDblProviders(),
                    cfg.getDblTimeoutSeconds());
            boolean listed = results.stream().anyMatch(DblResult::isListed);
            for (DblResult result : results) {
                b.evidence(result.getDblProvider(),
                        result.isListed() ? "LISTED " + result.getResponseRecords() : "clear");
            }
            checks.add(b.status(listed ? Status.FAIL : Status.PASS)
                    .summary(listed ? "Domain is listed by at least one configured DBL." :
                            "Domain was not listed by configured DBLs.")
                    .remediation(listed ? "Review the listed domain reputation result and remove abusive content or URLs." : null)
                    .build());
        }
        if (checks.isEmpty()) {
            checks.add(CheckResult.builder(Category.REPUTATION, "Domain reputation")
                    .status(Status.SKIPPED)
                    .summary("No message domains were available for DBL checks.")
                    .build());
        }
        return checks;
    }

    private CheckResult checkDomainAge(String domain, EmailAnalysisBotConfig cfg) {
        CheckResult.Builder b = CheckResult.builder(Category.REPUTATION, "Domain age: " + domain)
                .reference("RFC 9082")
                .reference("RFC 9083")
                .evidence("Domain", domain);
        DomainAge age = lookupDomainAge(domain, cfg.getDomainAgeTimeoutSeconds());
        if (age.registrationDate() == null) {
            return b.status(Status.INFO)
                    .summary("Domain registration date was not available from RDAP.")
                    .evidence("Lookup", age.message())
                    .build();
        }

        long days = ChronoUnit.DAYS.between(age.registrationDate(), LocalDate.now());
        b.evidence("Registration date", age.registrationDate().toString())
                .evidence("Age", days + " days");
        if (days >= 0 && days < cfg.getNewDomainWarnDays()) {
            return b.status(Status.WARN)
                    .summary("Domain appears recently registered.")
                    .remediation("Treat very new domains as a reputation signal and review surrounding authentication and content results.")
                    .build();
        }
        return b.status(Status.PASS)
                .summary("Domain registration age is outside the configured new-domain window.")
                .build();
    }

    private CheckResult checkSpf(MessageContext ctx) {
        RspamdSymbol symbol = ctx.findRspamdSymbol("R_SPF");
        CheckResult.Builder b = CheckResult.builder(Category.AUTHENTICATION, "SPF authentication")
                .reference("RFC 7208")
                .evidence("Envelope sender", nvl(ctx.envelopeSender(), "N/A"))
                .evidence("EHLO/HELO", nvl(ctx.ehlo(), "N/A"));
        if (symbol == null) {
            return b.status(Status.SKIPPED)
                    .summary("Rspamd did not return an SPF symbol.")
                    .remediation("Ensure Rspamd receives IP, Helo and From context for SPF checks.")
                    .build();
        }
        b.evidence("Rspamd symbol", symbol.name()).evidence("Details", symbol.details());
        return switch (symbol.name()) {
            case "R_SPF_ALLOW" -> b.status(Status.PASS).summary("SPF passed.").build();
            case "R_SPF_FAIL" -> b.status(Status.FAIL).summary("SPF hard failed.")
                    .remediation("Authorize this sending IP in the envelope sender domain SPF record, or correct the sender.")
                    .build();
            case "R_SPF_SOFTFAIL" -> b.status(Status.WARN).summary("SPF soft failed.")
                    .remediation("Review SPF policy and sender authorization; softfail is not a DMARC pass.")
                    .build();
            case "R_SPF_NEUTRAL" -> b.status(Status.WARN).summary("SPF returned neutral.")
                    .remediation("Publish a clearer SPF policy if this host is authorized.")
                    .build();
            case "R_SPF_DNSFAIL" -> b.status(Status.ERROR).summary("SPF had a DNS temporary failure.")
                    .remediation("Fix DNS availability for the sender domain.")
                    .build();
            case "R_SPF_PERMFAIL" -> b.status(Status.FAIL).summary("SPF has a permanent policy error.")
                    .remediation("Fix SPF syntax, duplicate records, or DNS lookup limits.")
                    .build();
            case "R_SPF_NA" -> b.status(Status.WARN).summary("No SPF policy was found.")
                    .remediation("Publish SPF for the envelope sender and HELO domain.")
                    .build();
            case "R_SPF_PLUSALL" -> b.status(Status.FAIL).summary("SPF contains +all.")
                    .remediation("Remove +all; it authorizes any sender.")
                    .build();
            default -> b.status(Status.INFO).summary("SPF returned " + symbol.name() + ".").build();
        };
    }

    private CheckResult checkDkim(MessageContext ctx) {
        RspamdSymbol symbol = ctx.findRspamdSymbol("R_DKIM");
        CheckResult.Builder b = CheckResult.builder(Category.AUTHENTICATION, "DKIM authentication")
                .reference("RFC 6376")
                .evidence("DKIM signature domains", formatSet(ctx.dkimDomains()));
        if (symbol == null) {
            return b.status(Status.SKIPPED)
                    .summary("Rspamd did not return a DKIM symbol.")
                    .build();
        }
        b.evidence("Rspamd symbol", symbol.name()).evidence("Details", symbol.details());
        return switch (symbol.name()) {
            case "R_DKIM_ALLOW" -> b.status(Status.PASS).summary("At least one DKIM signature verified.").build();
            case "R_DKIM_REJECT" -> b.status(Status.FAIL).summary("DKIM signature verification failed.")
                    .remediation("Check DKIM selector DNS, canonicalization, signed headers and body mutations.")
                    .build();
            case "R_DKIM_TEMPFAIL" -> b.status(Status.ERROR).summary("DKIM verification had a temporary error.")
                    .remediation("Check DNS availability for DKIM selector records.")
                    .build();
            case "R_DKIM_PERMFAIL" -> b.status(Status.FAIL).summary("DKIM verification had a permanent error.")
                    .remediation("Fix DKIM signature syntax or selector DNS.")
                    .build();
            case "R_DKIM_NA" -> b.status(Status.WARN).summary("No DKIM signature was present.")
                    .remediation("Sign outbound mail with DKIM.")
                    .build();
            default -> b.status(Status.INFO).summary("DKIM returned " + symbol.name() + ".").build();
        };
    }

    private List<CheckResult> checkDkimSelectors(MessageContext ctx) {
        List<CheckResult> checks = new ArrayList<>();
        for (DkimSignature sig : ctx.dkimSignatures()) {
            CheckResult.Builder b = CheckResult.builder(Category.DNS_PUBLISHING,
                    "DKIM selector DNS: " + sig.selector() + "._domainkey." + sig.domain())
                    .reference("RFC 6376")
                    .evidence("Signing domain", sig.domain())
                    .evidence("Selector", sig.selector())
                    .evidence("Algorithm", nvl(sig.algorithm(), "N/A"));
            if (isBlank(sig.domain()) || isBlank(sig.selector())) {
                checks.add(b.status(Status.FAIL)
                        .summary("DKIM signature is missing d= or s=.")
                        .remediation("Emit DKIM signatures with both domain and selector tags.")
                        .build());
                continue;
            }
            String name = sig.selector() + "._domainkey." + sig.domain();
            List<String> txt = txtRecords(name);
            b.evidence("TXT records", txt.isEmpty() ? "none" : String.join(" | ", txt));
            if (txt.isEmpty()) {
                checks.add(b.status(Status.FAIL)
                        .summary("DKIM selector TXT record was not found.")
                        .remediation("Publish the public key at " + name + ".")
                        .build());
            } else if (sig.algorithm() != null && sig.algorithm().toLowerCase(Locale.ROOT).contains("sha1")) {
                checks.add(b.status(Status.WARN)
                        .summary("DKIM signature uses SHA-1.")
                        .remediation("Use a modern DKIM signing algorithm such as rsa-sha256 or ed25519-sha256.")
                        .build());
            } else {
                checks.add(b.status(Status.PASS)
                        .summary("DKIM selector TXT record exists.")
                        .build());
            }
        }
        return checks;
    }

    private CheckResult checkDmarc(MessageContext ctx) {
        RspamdSymbol symbol = ctx.findRspamdSymbol("DMARC");
        CheckResult.Builder b = CheckResult.builder(Category.AUTHENTICATION, "DMARC compliance")
                .reference("RFC 7489")
                .evidence("Header From domain", nvl(ctx.headerFromDomain(), "N/A"));
        if (symbol == null) {
            return b.status(Status.SKIPPED)
                    .summary("Rspamd did not return a DMARC symbol.")
                    .build();
        }
        b.evidence("Rspamd symbol", symbol.name()).evidence("Details", symbol.details());
        return switch (symbol.name()) {
            case "DMARC_POLICY_ALLOW" -> b.status(Status.PASS).summary("DMARC passed.").build();
            case "DMARC_POLICY_REJECT" -> b.status(Status.FAIL).summary("DMARC failed with reject policy.")
                    .remediation("Align SPF or DKIM with the header From domain.")
                    .build();
            case "DMARC_POLICY_QUARANTINE" -> b.status(Status.FAIL).summary("DMARC failed with quarantine policy.")
                    .remediation("Align SPF or DKIM with the header From domain.")
                    .build();
            case "DMARC_POLICY_SOFTFAIL" -> b.status(Status.WARN).summary("DMARC failed under p=none or sampled policy.")
                    .remediation("Align SPF or DKIM before moving to quarantine/reject.")
                    .build();
            case "DMARC_NA" -> b.status(Status.WARN).summary("No DMARC policy was found.")
                    .remediation("Publish a DMARC record for the header From domain.")
                    .build();
            case "DMARC_BAD_POLICY" -> b.status(Status.FAIL).summary("DMARC policy is invalid or duplicated.")
                    .remediation("Fix the _dmarc TXT record.")
                    .build();
            case "DMARC_DNSFAIL" -> b.status(Status.ERROR).summary("DMARC DNS lookup failed.")
                    .remediation("Fix DNS availability for the _dmarc record.")
                    .build();
            default -> b.status(Status.INFO).summary("DMARC returned " + symbol.name() + ".").build();
        };
    }

    private CheckResult checkDmarcRecord(MessageContext ctx) {
        String domain = ctx.headerFromDomain();
        CheckResult.Builder b = CheckResult.builder(Category.DNS_PUBLISHING, "DMARC DNS record")
                .reference("RFC 7489")
                .evidence("Header From domain", nvl(domain, "N/A"));
        if (isBlank(domain)) {
            return b.status(Status.SKIPPED).summary("No header From domain was available.").build();
        }
        List<String> txt = txtRecords("_dmarc." + domain);
        List<String> dmarc = txt.stream()
                .filter(v -> v.toLowerCase(Locale.ROOT).startsWith("v=dmarc1"))
                .toList();
        b.evidence("_dmarc TXT", dmarc.isEmpty() ? "none" : String.join(" | ", dmarc));
        if (dmarc.isEmpty()) {
            return b.status(Status.WARN)
                    .summary("No DMARC record was found.")
                    .remediation("Publish _dmarc." + domain + " TXT with at least v=DMARC1; p=none.")
                    .build();
        }
        if (dmarc.size() > 1) {
            return b.status(Status.FAIL)
                    .summary("Multiple DMARC records were found.")
                    .remediation("Publish exactly one DMARC TXT record.")
                    .build();
        }
        Map<String, String> tags = tagMap(dmarc.getFirst());
        b.evidence("Policy", nvl(tags.get("p"), "missing"))
                .evidence("Subdomain policy", nvl(tags.get("sp"), "not set"))
                .evidence("Aggregate reports", nvl(tags.get("rua"), "not set"));
        if (!tags.containsKey("p")) {
            return b.status(Status.FAIL)
                    .summary("DMARC record has no p= policy.")
                    .remediation("Add p=none, p=quarantine or p=reject.")
                    .build();
        }
        return b.status(Status.PASS)
                .summary("A single DMARC record was found.")
                .build();
    }

    private List<CheckResult> checkMxDomain(String domain, MessageContext ctx, EmailAnalysisBotConfig cfg) {
        List<CheckResult> checks = new ArrayList<>();
        CheckResult.Builder mxBuilder = CheckResult.builder(Category.MX_RECEIVING, "MX records: " + domain)
                .reference("RFC 5321 5")
                .reference("RFC 2181 10.3")
                .evidence("Domain", domain);
        List<DnsRecord> mxRecords;
        try {
            mxRecords = new MXResolver().resolveMx(domain);
        } catch (Exception e) {
            checks.add(mxBuilder.status(Status.ERROR)
                    .summary("MX lookup failed: " + e.getMessage())
                    .build());
            return checks;
        }
        if (mxRecords.isEmpty()) {
            checks.add(mxBuilder.status(Status.FAIL)
                    .summary("No MX or implicit A/AAAA route was found.")
                    .remediation("Publish MX records or ensure the domain has address records for implicit MX fallback.")
                    .build());
            return checks;
        }
        for (DnsRecord mx : mxRecords) {
            String host = trimDot(mx.getValue());
            boolean cname = hasRecord(host, Type.CNAME);
            Set<String> addresses = resolveHostAddresses(host);
            mxBuilder.evidence(mx.getPriority() + " " + host,
                    (addresses.isEmpty() ? "no A/AAAA" : formatSet(addresses)) + (cname ? "; CNAME target" : ""));
            checks.add(checkMxPort(domain, host, cfg));
            if (cfg.isRecipientProbeEnabled()) {
                checks.addAll(checkSenderAddresses(domain, host, ctx, cfg));
                checks.addAll(checkRoleAddresses(domain, host, cfg));
            }
        }
        boolean anyBadTarget = mxRecords.stream()
                .map(r -> trimDot(r.getValue()))
                .anyMatch(host -> hasRecord(host, Type.CNAME) || resolveHostAddresses(host).isEmpty() || isIpLiteral(host));
        checks.addFirst(mxBuilder.status(anyBadTarget ? Status.FAIL : Status.PASS)
                .summary(anyBadTarget ? "At least one MX target is not RFC-correct." :
                        "MX targets resolve to address records and are not CNAME aliases.")
                .remediation(anyBadTarget ? "Point MX records directly at hostnames that own A/AAAA records." : null)
                .build());
        return checks;
    }

    private List<CheckResult> checkSenderAddresses(String domain, String host, MessageContext ctx, EmailAnalysisBotConfig cfg) {
        List<CheckResult> checks = new ArrayList<>();
        for (String address : senderAddressesForDomain(domain, ctx)) {
            SmtpProbeResult probe = probeRecipient(host, cfg.getProbeMailFrom(), address,
                    cfg.getProbeEhloName(), cfg.getRecipientProbeTimeoutSeconds());
            checks.add(CheckResult.builder(Category.MX_RECEIVING, "Sender mailbox: " + address)
                    .reference("RFC 5321 RCPT command")
                    .status(probe.accepted() ? Status.PASS : Status.WARN)
                    .summary(probe.summary())
                    .evidence("MX host", host)
                    .evidence("SMTP reply", probe.reply())
                    .remediation(probe.accepted() ? null :
                            "Confirm the sender address is intended to receive replies or bounces.")
                    .build());
        }
        return checks;
    }

    private CheckResult checkMxPort(String domain, String host, EmailAnalysisBotConfig cfg) {
        CheckResult.Builder b = CheckResult.builder(Category.MX_RECEIVING, "MX SMTP port 25: " + host)
                .reference("RFC 5321")
                .reference("RFC 3207")
                .evidence("Domain", domain)
                .evidence("MX host", host);
        List<PortTlsResult> results = PortTlsChecker.checkPorts(host, List.of(25), cfg.getPortCheckTimeoutSeconds());
        if (results.isEmpty()) {
            return b.status(Status.ERROR).summary("Port probe did not return a result.").build();
        }
        PortTlsResult result = results.getFirst();
        b.evidence("Open", yesNo(result.isOpen()))
                .evidence("STARTTLS/TLS", result.getTlsStatus().name())
                .evidence("Certificate expiry", result.getCertExpiry() != null ? result.getCertExpiry().toString() : "N/A")
                .evidence("Certificate subject", nvl(result.getCertSubject(), "N/A"));
        if (!result.isOpen()) {
            return b.status(Status.FAIL)
                    .summary("MX host did not accept SMTP on port 25.")
                    .remediation("Ensure every advertised MX can receive SMTP on port 25.")
                    .build();
        }
        if (result.isCertExpiringSoon()) {
            return b.status(Status.WARN)
                    .summary("MX port 25 is open, but its TLS certificate expires soon or is expired.")
                    .remediation("Renew or replace the SMTP TLS certificate.")
                    .build();
        }
        return b.status(Status.PASS)
                .summary("MX SMTP port 25 is reachable.")
                .build();
    }

    private List<CheckResult> checkRoleAddresses(String domain, String host, EmailAnalysisBotConfig cfg) {
        List<CheckResult> checks = new ArrayList<>();
        for (String local : cfg.getRoleAliases()) {
            SmtpProbeResult probe = probeRecipient(host, cfg.getProbeMailFrom(), local + "@" + domain,
                    cfg.getProbeEhloName(), cfg.getRecipientProbeTimeoutSeconds());
            Status status = probe.accepted() ? Status.PASS : ("postmaster".equalsIgnoreCase(local) ? Status.FAIL : Status.WARN);
            checks.add(CheckResult.builder(Category.MX_RECEIVING, "Role address: " + local + "@" + domain)
                    .reference("postmaster".equalsIgnoreCase(local) ? "RFC 5321 4.5.1" : "RFC 2142")
                    .status(status)
                    .summary(probe.summary())
                    .evidence("MX host", host)
                    .evidence("SMTP reply", probe.reply())
                    .remediation(probe.accepted() ? null : "Ensure " + local + "@" + domain + " is accepted or intentionally documented.")
                    .build());
        }
        return checks;
    }

    private CheckResult checkSendingHostPorts(MessageContext ctx, EmailAnalysisBotConfig cfg) {
        CheckResult.Builder b = CheckResult.builder(Category.SENDING_HOST, "Sending host SMTP port")
                .reference("Operational check; not an RFC failure unless the sender is also an MX")
                .evidence("Connecting IP", nvl(ctx.remoteIp(), "N/A"))
                .evidence("EHLO/HELO", nvl(ctx.ehlo(), "N/A"))
                .evidence("PTR", nvl(ctx.rdns(), "N/A"));
        List<String> hosts = new ArrayList<>();
        if (!isBlank(ctx.remoteIp())) hosts.add(ctx.remoteIp());
        if (!isBlank(ctx.ehlo()) && !ctx.ehlo().startsWith("[")) hosts.add(trimDot(ctx.ehlo()));
        if (!isBlank(ctx.rdns())) hosts.add(trimDot(ctx.rdns()));
        hosts = hosts.stream().filter(s -> !isBlank(s)).distinct().toList();
        if (hosts.isEmpty()) {
            return b.status(Status.SKIPPED).summary("No sending host names or IP were available.").build();
        }
        boolean anyOpen = false;
        for (String host : hosts) {
            List<PortTlsResult> results = PortTlsChecker.checkPorts(host, cfg.getPortCheckPorts(), cfg.getPortCheckTimeoutSeconds());
            for (PortTlsResult result : results) {
                anyOpen |= result.isOpen();
                b.evidence(host + ":" + result.getPort(),
                        (result.isOpen() ? "open" : "closed") + ", TLS=" + result.getTlsStatus());
            }
        }
        return b.status(anyOpen ? Status.INFO : Status.INFO)
                .summary(anyOpen ? "At least one configured SMTP port was reachable on the sending host." :
                        "No configured SMTP ports were reachable on the sending host. This is not a failure unless this host is also an MX.")
                .build();
    }

    private CheckResult checkMtaSts(String domain) {
        CheckResult.Builder b = CheckResult.builder(Category.TRANSPORT_SECURITY, "MTA-STS: " + domain)
                .reference("RFC 8461")
                .evidence("Domain", domain);
        try {
            var policy = new StrictMx(domain).getPolicy();
            if (policy == null) {
                return b.status(Status.INFO).summary("No MTA-STS policy was found.").build();
            }
            b.evidence("Mode", policy.getMode().toString())
                    .evidence("Max age", String.valueOf(policy.getMaxAge()))
                    .evidence("Allowed MX", String.join(", ", policy.getMxMasks()));
            return b.status(policy.isValid() ? Status.PASS : Status.FAIL)
                    .summary(policy.isValid() ? "MTA-STS policy is published and valid." :
                            "MTA-STS policy is published but invalid.")
                    .remediation(policy.isValid() ? null : "Fix the HTTPS MTA-STS policy file.")
                    .build();
        } catch (Exception e) {
            return b.status(Status.ERROR).summary("MTA-STS check failed: " + e.getMessage()).build();
        }
    }

    private CheckResult checkTlsRpt(String domain) {
        CheckResult.Builder b = CheckResult.builder(Category.TRANSPORT_SECURITY, "TLSRPT: " + domain)
                .reference("RFC 8460")
                .evidence("Domain", domain);
        try {
            Optional<StsReport> rpt = new XBillDnsRecordClient().getRptRecord(domain);
            if (rpt.isEmpty()) {
                return b.status(Status.INFO).summary("No TLSRPT record was found.").build();
            }
            b.evidence("rua", String.join(", ", rpt.get().getRua()));
            return b.status(rpt.get().isValid() ? Status.PASS : Status.FAIL)
                    .summary(rpt.get().isValid() ? "TLSRPT record is valid." : "TLSRPT record is invalid.")
                    .build();
        } catch (Exception e) {
            return b.status(Status.ERROR).summary("TLSRPT check failed: " + e.getMessage()).build();
        }
    }

    private CheckResult checkDane(String domain) {
        CheckResult.Builder b = CheckResult.builder(Category.TRANSPORT_SECURITY, "DANE/TLSA: " + domain)
                .reference("RFC 7672")
                .evidence("Domain", domain);
        try {
            List<DnsRecord> mxRecords = new MXResolver().resolveMx(domain);
            boolean anyDane = false;
            for (DnsRecord mx : mxRecords) {
                List<DaneRecord> tlsa = DaneChecker.checkDane(mx.getValue());
                anyDane |= !tlsa.isEmpty();
                b.evidence(trimDot(mx.getValue()), tlsa.isEmpty() ? "no TLSA" : tlsa.size() + " TLSA record(s)");
            }
            return b.status(anyDane ? Status.PASS : Status.INFO)
                    .summary(anyDane ? "At least one MX publishes TLSA records." :
                            "No TLSA records were found for MX hosts.")
                    .remediation(anyDane ? "Ensure DNSSEC validation is working; DANE depends on secure DNS." : null)
                    .build();
        } catch (Exception e) {
            return b.status(Status.ERROR).summary("DANE check failed: " + e.getMessage()).build();
        }
    }

    private CheckResult checkSpam(MessageContext ctx) {
        CheckResult.Builder b = CheckResult.builder(Category.MESSAGE_CONTENT, "Rspamd spam analysis");
        Map<String, Object> result = ctx.rspamdResult();
        if (result.isEmpty()) {
            return b.status(Status.SKIPPED).summary("No Rspamd result was available.").build();
        }
        Object score = result.get("score");
        Object spam = result.get("spam");
        b.evidence("Score", String.valueOf(score))
                .evidence("Spam", String.valueOf(spam));
        Map<String, Object> symbols = ctx.rspamdSymbols();
        symbols.entrySet().stream()
                .sorted(Comparator.comparingDouble(e -> -Math.abs(symbolScore(e.getValue()))))
                .limit(12)
                .forEach(e -> b.evidence(e.getKey(), String.valueOf(symbolScore(e.getValue())) + " " + symbolDescription(e.getValue())));
        return b.status(Boolean.TRUE.equals(spam) ? Status.FAIL : Status.PASS)
                .summary(Boolean.TRUE.equals(spam) ? "Rspamd classified the message as spam." :
                        "Rspamd did not classify the message as spam.")
                .build();
    }

    private CheckResult checkMessageHeaders(MessageContext ctx) {
        CheckResult.Builder b = CheckResult.builder(Category.MESSAGE_CONTENT, "Message header sanity")
                .reference("RFC 5322")
                .evidence("Header From", nvl(ctx.headerFrom(), "N/A"));
        List<String> missing = new ArrayList<>();
        for (String header : List.of("From", "Date", "Message-ID", "Subject")) {
            if (isBlank(ctx.header(header))) missing.add(header);
        }
        b.evidence("Missing headers", missing.isEmpty() ? "none" : String.join(", ", missing));
        if (!missing.isEmpty()) {
            return b.status(Status.WARN)
                    .summary("Some common RFC 5322/deliverability headers are missing.")
                    .remediation("Ensure generated mail includes From, Date, Message-ID and Subject.")
                    .build();
        }
        return b.status(Status.PASS).summary("Common message headers are present.").build();
    }

    private CheckResult checkListUnsubscribe(MessageContext ctx) {
        CheckResult.Builder b = CheckResult.builder(Category.MESSAGE_CONTENT, "List-Unsubscribe")
                .reference("RFC 2369")
                .reference("RFC 8058")
                .evidence("List-Unsubscribe", nvl(ctx.header("List-Unsubscribe"), "N/A"))
                .evidence("List-Unsubscribe-Post", nvl(ctx.header("List-Unsubscribe-Post"), "N/A"));
        boolean looksBulk = !isBlank(ctx.header("List-ID")) || !isBlank(ctx.header("List-Unsubscribe")) ||
                ctx.rspamdSymbols().containsKey("MAILLIST");
        if (!looksBulk) {
            return b.status(Status.SKIPPED).summary("Message does not appear list or bulk-like.").build();
        }
        if (isBlank(ctx.header("List-Unsubscribe"))) {
            return b.status(Status.WARN)
                    .summary("List-like message does not include List-Unsubscribe.")
                    .remediation("Add List-Unsubscribe for list or promotional mail.")
                    .build();
        }
        if ("List-Unsubscribe=One-Click".equals(ctx.header("List-Unsubscribe-Post"))) {
            return b.status(Status.PASS).summary("One-click unsubscribe headers are present.").build();
        }
        return b.status(Status.WARN)
                .summary("List-Unsubscribe is present, but one-click signalling is missing.")
                .remediation("For promotional/list mail, add List-Unsubscribe-Post: List-Unsubscribe=One-Click with an HTTPS URL.")
                .build();
    }

    private static SmtpProbeResult probeRecipient(String host, String mailFrom, String rcptTo,
                                                  String ehlo, int timeoutSeconds) {
        int timeoutMs = Math.max(1, timeoutSeconds) * 1000;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, 25), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);

            String banner = readReply(in);
            if (!startsWith2xx(banner)) return new SmtpProbeResult(false, banner, "SMTP banner was not successful.");
            out.print("EHLO " + ehlo + "\r\n");
            out.flush();
            String ehloReply = readReply(in);
            if (!startsWith2xx(ehloReply)) {
                out.print("HELO " + ehlo + "\r\n");
                out.flush();
                readReply(in);
            }
            out.print("MAIL FROM:<" + nullToEmpty(mailFrom) + ">\r\n");
            out.flush();
            String mailReply = readReply(in);
            if (!startsWith2xx(mailReply)) return new SmtpProbeResult(false, mailReply, "MAIL FROM probe was rejected.");
            out.print("RCPT TO:<" + rcptTo + ">\r\n");
            out.flush();
            String rcptReply = readReply(in);
            out.print("RSET\r\n");
            out.flush();
            readReply(in);
            out.print("QUIT\r\n");
            out.flush();

            int code = replyCode(rcptReply);
            if (code >= 200 && code < 300) return new SmtpProbeResult(true, rcptReply, "Recipient was accepted.");
            if (code >= 400 && code < 500) return new SmtpProbeResult(false, rcptReply, "Recipient probe returned a temporary failure.");
            return new SmtpProbeResult(false, rcptReply, "Recipient was rejected.");
        } catch (Exception e) {
            return new SmtpProbeResult(false, e.getMessage(), "Recipient probe failed.");
        }
    }

    private static DomainAge lookupDomainAge(String domain, int timeoutSeconds) {
        if (isBlank(domain)) return new DomainAge(null, "No domain was available.");
        int timeout = Math.max(1, timeoutSeconds);
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .callTimeout(timeout + 2L, TimeUnit.SECONDS)
                .build();
        String asciiDomain;
        try {
            asciiDomain = IDN.toASCII(trimDot(domain));
        } catch (Exception e) {
            return new DomainAge(null, "Invalid domain name: " + e.getMessage());
        }

        Request request = new Request.Builder()
                .url("https://rdap.org/domain/" + asciiDomain)
                .header("Accept", "application/rdap+json, application/json")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return new DomainAge(null, "RDAP HTTP " + response.code());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> body = GSON.fromJson(response.body().string(), Map.class);
            LocalDate registration = registrationDateFromRdap(body);
            return new DomainAge(registration, registration == null ? "No registration event in RDAP response." : "RDAP registration event found.");
        } catch (Exception e) {
            return new DomainAge(null, e.getMessage());
        }
    }

    private void queueResponse(Session session, String botAddress, String replyTo, AnalysisReport report) {
        try {
            List<MimePart> parts = new ArrayList<>();
            parts.add(new TextMimePart(renderText(report).getBytes(StandardCharsets.UTF_8))
                    .addHeader("Content-Type", "text/plain; charset=\"UTF-8\"")
                    .addHeader("Content-Transfer-Encoding", "8bit"));
            parts.add(new TextMimePart(renderHtml(report).getBytes(StandardCharsets.UTF_8))
                    .addHeader("Content-Type", "text/html; charset=\"UTF-8\"")
                    .addHeader("Content-Transfer-Encoding", "8bit"));

            BotHelper.queueBotResponse(session, botAddress, replyTo,
                    "Robin Email Analysis BOT - " + session.getUID(), parts);
        } catch (IOException e) {
            log.error("Failed to queue analysis response: {}", e.getMessage(), e);
        }
    }

    static String renderText(AnalysisReport report) {
        StringBuilder out = new StringBuilder();
        out.append("Robin Email Analysis Report\n");
        out.append("===========================\n\n");
        out.append("Generated: ").append(report.generatedAt()).append('\n');
        out.append("Session UID: ").append(report.sessionUid()).append('\n');
        out.append("Connecting IP: ").append(nvl(report.context().remoteIp(), "N/A")).append('\n');
        out.append("EHLO/HELO: ").append(nvl(report.context().ehlo(), "N/A")).append('\n');
        out.append("Envelope sender: ").append(nvl(report.context().envelopeSender(), "N/A")).append('\n');
        out.append("Header From: ").append(nvl(report.context().headerFrom(), "N/A")).append("\n\n");
        out.append("Summary: ").append(report.overall()).append(" (")
                .append(report.count(Status.PASS)).append(" pass, ")
                .append(report.count(Status.WARN)).append(" warn, ")
                .append(report.count(Status.FAIL)).append(" fail, ")
                .append(report.count(Status.ERROR)).append(" error)\n\n");

        for (Category category : Category.values()) {
            List<CheckResult> checks = report.byCategory(category);
            if (checks.isEmpty()) continue;
            out.append(category.label()).append('\n');
            out.append("-".repeat(category.label().length())).append('\n');
            for (CheckResult check : checks) {
                out.append('[').append(check.status()).append("] ").append(check.name()).append('\n');
                out.append("  ").append(check.summary()).append('\n');
                check.evidence().forEach((k, v) -> out.append("  - ").append(k).append(": ").append(v).append('\n'));
                if (!isBlank(check.remediation())) {
                    out.append("  Fix: ").append(check.remediation()).append('\n');
                }
                if (!check.references().isEmpty()) {
                    out.append("  References: ").append(String.join(", ", check.references())).append('\n');
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    static String renderHtml(AnalysisReport report) {
        StringBuilder out = new StringBuilder();
        out.append("<!doctype html><html><head><meta charset=\"UTF-8\"><style>");
        out.append("body{font-family:Arial,sans-serif;background:#f6f8fb;color:#1f2937;margin:0;padding:24px}");
        out.append(".wrap{max-width:980px;margin:0 auto;background:#fff;border:1px solid #d8dee8;border-radius:8px;overflow:hidden}");
        out.append(".head{padding:22px 26px;background:#19324d;color:#fff}.head h1{margin:0 0 8px;font-size:22px}");
        out.append(".meta{font-size:13px;line-height:1.6;color:#dce7f3}.summary{display:flex;gap:10px;flex-wrap:wrap;padding:18px 26px;border-bottom:1px solid #e5e7eb}");
        out.append(".pill{border-radius:4px;padding:6px 9px;font-weight:700;font-size:12px}.PASS{background:#dcfce7;color:#166534}.WARN{background:#fef3c7;color:#92400e}.FAIL{background:#fee2e2;color:#991b1b}.ERROR{background:#ede9fe;color:#5b21b6}.INFO,.SKIPPED{background:#e0f2fe;color:#075985}");
        out.append("section{padding:18px 26px;border-bottom:1px solid #e5e7eb}h2{font-size:17px;margin:0 0 12px}.check{border:1px solid #e5e7eb;border-left-width:5px;border-radius:6px;margin:10px 0;padding:12px 14px}.check.PASS{border-left-color:#22c55e}.check.WARN{border-left-color:#f59e0b}.check.FAIL{border-left-color:#ef4444}.check.ERROR{border-left-color:#7c3aed}.check.INFO,.check.SKIPPED{border-left-color:#0ea5e9}");
        out.append(".title{display:flex;align-items:center;gap:8px;font-weight:700}.details{margin-top:8px;font-size:13px}.details div{padding:3px 0}.fix{margin-top:8px;background:#fff7ed;border:1px solid #fed7aa;padding:8px;border-radius:4px;font-size:13px}.refs{margin-top:8px;color:#64748b;font-size:12px}");
        out.append("</style></head><body><div class=\"wrap\">");
        out.append("<div class=\"head\"><h1>Robin Email Analysis Report</h1><div class=\"meta\">");
        out.append("Generated: ").append(esc(report.generatedAt().toString())).append("<br>");
        out.append("Session UID: ").append(esc(report.sessionUid())).append("<br>");
        out.append("Connecting IP: ").append(esc(nvl(report.context().remoteIp(), "N/A"))).append(" | EHLO/HELO: ")
                .append(esc(nvl(report.context().ehlo(), "N/A"))).append("<br>");
        out.append("Envelope sender: ").append(esc(nvl(report.context().envelopeSender(), "N/A")))
                .append(" | Header From: ").append(esc(nvl(report.context().headerFrom(), "N/A")));
        out.append("</div></div>");
        out.append("<div class=\"summary\"><span class=\"pill ").append(report.overall()).append("\">Overall: ")
                .append(report.overall()).append("</span>");
        for (Status status : List.of(Status.PASS, Status.WARN, Status.FAIL, Status.ERROR, Status.INFO, Status.SKIPPED)) {
            out.append("<span class=\"pill ").append(status).append("\">")
                    .append(status).append(": ").append(report.count(status)).append("</span>");
        }
        out.append("</div>");

        for (Category category : Category.values()) {
            List<CheckResult> checks = report.byCategory(category);
            if (checks.isEmpty()) continue;
            out.append("<section><h2>").append(esc(category.label())).append("</h2>");
            for (CheckResult check : checks) {
                out.append("<div class=\"check ").append(check.status()).append("\"><div class=\"title\"><span class=\"pill ")
                        .append(check.status()).append("\">").append(check.status()).append("</span>")
                        .append(esc(check.name())).append("</div>");
                out.append("<div class=\"details\"><div>").append(esc(check.summary())).append("</div>");
                check.evidence().forEach((k, v) -> out.append("<div><strong>").append(esc(k)).append(":</strong> ")
                        .append(esc(v)).append("</div>"));
                out.append("</div>");
                if (!isBlank(check.remediation())) out.append("<div class=\"fix\">").append(esc(check.remediation())).append("</div>");
                if (!check.references().isEmpty()) out.append("<div class=\"refs\">References: ")
                        .append(esc(String.join(", ", check.references()))).append("</div>");
                out.append("</div>");
            }
            out.append("</section>");
        }
        out.append("</div></body></html>");
        return out.toString();
    }

    @Override
    public String getName() {
        return "email";
    }

    private static MessageEnvelope currentEnvelope(Session session) {
        return session != null && !session.getEnvelopes().isEmpty() ? session.getEnvelopes().getLast() : null;
    }

    private static String headerValue(EmailParser parser, String name) {
        if (parser == null) return null;
        for (MimeHeader header : parser.getHeaders().get()) {
            if (header.getName().equalsIgnoreCase(name)) return header.getValue();
        }
        return null;
    }

    private static String firstEmail(String value) {
        if (isBlank(value)) return null;
        Matcher matcher = SIMPLE_EMAIL.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String domainFromEmail(String email) {
        if (isBlank(email) || !email.contains("@")) return null;
        String domain = email.substring(email.lastIndexOf('@') + 1).trim().toLowerCase(Locale.ROOT);
        return domain.isEmpty() ? null : trimDot(domain);
    }

    private static boolean isValidSmtpDomain(String value) {
        if (isBlank(value) || value.length() > 255 || !value.contains(".")) return false;
        for (String label : value.split("\\.")) {
            if (!DOMAIN_LABEL.matcher(label).matches()) return false;
        }
        return true;
    }

    private static Set<String> resolveHostAddresses(String host) {
        if (isBlank(host)) return Collections.emptySet();
        try {
            Set<String> results = new LinkedHashSet<>();
            for (InetAddress address : Address.getAllByName(host)) {
                results.add(address.getHostAddress());
            }
            return results;
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    private static List<String> txtRecords(String name) {
        try {
            Record[] records = new Lookup(name, Type.TXT).run();
            if (records == null) return Collections.emptyList();
            List<String> values = new ArrayList<>();
            for (Record record : records) {
                String raw = record.rdataToString();
                values.add(raw.replace("\" \"", "").replace("\"", ""));
            }
            return values;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static boolean hasRecord(String name, int type) {
        try {
            Record[] records = new Lookup(name, type).run();
            return records != null && records.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isIpLiteral(String host) {
        if (isBlank(host)) return false;
        try {
            InetAddress.getByName(host);
            return host.matches("^[0-9.]+$") || host.contains(":");
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, String> tagMap(String value) {
        Map<String, String> tags = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (value == null) return tags;
        for (String part : value.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2) tags.put(kv[0].trim().toLowerCase(Locale.ROOT), kv[1].trim());
        }
        return tags;
    }

    private static DkimSignature parseDkimSignature(String value) {
        Map<String, String> tags = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Matcher matcher = DKIM_TAG.matcher(value == null ? "" : value);
        while (matcher.find()) {
            tags.put(matcher.group(2).toLowerCase(Locale.ROOT), matcher.group(3).trim());
        }
        return new DkimSignature(tags.get("d"), tags.get("s"), tags.get("a"));
    }

    private static String readReply(BufferedReader in) throws IOException {
        StringBuilder reply = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            if (!reply.isEmpty()) reply.append('\n');
            reply.append(line);
            if (line.length() < 4 || line.charAt(3) != '-') break;
        }
        return reply.toString();
    }

    private static boolean startsWith2xx(String reply) {
        int code = replyCode(reply);
        return code >= 200 && code < 300;
    }

    private static int replyCode(String reply) {
        if (reply == null || reply.length() < 3) return -1;
        try {
            return Integer.parseInt(reply.substring(0, 3));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double symbolScore(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof Map<?, ?> map && map.get("score") instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private static String symbolDescription(Object value) {
        if (value instanceof Map<?, ?> map && map.get("description") != null) {
            return String.valueOf(map.get("description"));
        }
        return "";
    }

    private static LocalDate registrationDateFromRdap(Map<String, Object> body) {
        if (body == null) return null;
        Object events = body.get("events");
        if (!(events instanceof List<?> list)) return null;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> event)) continue;
            String action = String.valueOf(event.get("eventAction")).toLowerCase(Locale.ROOT);
            if (!action.contains("registration") && !action.contains("registered")) continue;
            Object date = event.get("eventDate");
            if (date == null) continue;
            try {
                return OffsetDateTime.parse(String.valueOf(date)).toLocalDate();
            } catch (DateTimeParseException ignored) {
                try {
                    return LocalDate.parse(String.valueOf(date).substring(0, 10));
                } catch (Exception ignoredAgain) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String literalValue(String ehloLiteral) {
        String value = ehloLiteral.substring(1, ehloLiteral.length() - 1);
        if (value.toLowerCase(Locale.ROOT).startsWith("ipv6:")) return value.substring(5);
        return value;
    }

    private static String trimDot(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String formatSet(Set<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join(", ", values);
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private static String nvl(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private enum Status {
        PASS, WARN, FAIL, INFO, ERROR, SKIPPED
    }

    enum Category {
        SENDING_HOST("Sending Host"),
        AUTHENTICATION("Authentication"),
        REPUTATION("Reputation"),
        MX_RECEIVING("MX Receiving"),
        TRANSPORT_SECURITY("Transport Security"),
        DNS_PUBLISHING("DNS Publishing"),
        MESSAGE_CONTENT("Message Content");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record CheckResult(Category category, String name, Status status, String summary,
                       Map<String, String> evidence, String remediation, List<String> references) {
        static Builder builder(Category category, String name) {
            return new Builder(category, name);
        }

        static final class Builder {
            private final Category category;
            private final String name;
            private Status status = Status.INFO;
            private String summary = "";
            private final Map<String, String> evidence = new LinkedHashMap<>();
            private String remediation;
            private final List<String> references = new ArrayList<>();

            private Builder(Category category, String name) {
                this.category = category;
                this.name = name;
            }

            Builder status(Status status) {
                this.status = status;
                return this;
            }

            Builder summary(String summary) {
                this.summary = summary;
                return this;
            }

            Builder evidence(String key, String value) {
                if (!isBlank(key) && value != null) evidence.put(key, value);
                return this;
            }

            Builder remediation(String remediation) {
                this.remediation = remediation;
                return this;
            }

            Builder reference(String reference) {
                if (!isBlank(reference)) references.add(reference);
                return this;
            }

            CheckResult build() {
                return new CheckResult(category, name, status, summary,
                        Collections.unmodifiableMap(new LinkedHashMap<>(evidence)),
                        remediation,
                        List.copyOf(references));
            }
        }
    }

    record AnalysisReport(String sessionUid, LocalDateTime generatedAt, MessageContext context,
                          List<CheckResult> checks) {
        AnalysisReport(String sessionUid, LocalDateTime generatedAt, MessageContext context) {
            this(sessionUid, generatedAt, context, new ArrayList<>());
        }

        void add(CheckResult check) {
            if (check != null) checks.add(check);
        }

        void addAll(List<CheckResult> items) {
            if (items != null) items.stream().filter(Objects::nonNull).forEach(checks::add);
        }

        List<CheckResult> byCategory(Category category) {
            return checks.stream().filter(c -> c.category() == category).toList();
        }

        long count(Status status) {
            return checks.stream().filter(c -> c.status() == status).count();
        }

        Status overall() {
            if (count(Status.FAIL) > 0) return Status.FAIL;
            if (count(Status.ERROR) > 0) return Status.ERROR;
            if (count(Status.WARN) > 0) return Status.WARN;
            return Status.PASS;
        }
    }

    record RspamdSymbol(String name, Object raw) {
        String details() {
            if (raw instanceof Map<?, ?> map) {
                Object description = map.get("description");
                Object options = map.get("options");
                if (description != null && options != null) return description + " " + options;
                if (description != null) return String.valueOf(description);
                if (options != null) return String.valueOf(options);
            }
            return raw == null ? "" : String.valueOf(raw);
        }
    }

    record DkimSignature(String domain, String selector, String algorithm) {
    }

    record SmtpProbeResult(boolean accepted, String reply, String summary) {
    }

    record DomainAge(LocalDate registrationDate, String message) {
    }

    record MessageContext(Session session, MessageEnvelope envelope, EmailParser parser,
                          String remoteIp, String rdns, String ehlo, String envelopeSender,
                          String envelopeDomain, String headerFrom, String headerFromDomain,
                          List<DkimSignature> dkimSignatures, Map<String, Object> rspamdResult) {

        static MessageContext from(Session session, MessageEnvelope envelope, EmailParser parser) {
            String headerFrom = firstNonBlank(
                    headerValue(parser, "From"),
                    envelope != null ? envelope.getHeaders().get("X-Parsed-From") : null);
            String fromEmail = firstEmail(headerFrom);
            List<DkimSignature> signatures = new ArrayList<>();
            if (parser != null) {
                for (MimeHeader header : parser.getHeaders().get()) {
                    if ("DKIM-Signature".equalsIgnoreCase(header.getName())) {
                        signatures.add(parseDkimSignature(header.getValue()));
                    }
                }
            }
            Map<String, Object> rspamd = Collections.emptyMap();
            if (envelope != null) {
                for (Map<String, Object> result : envelope.getScanResults()) {
                    if ("rspamd".equals(result.get("scanner"))) {
                        rspamd = result;
                        break;
                    }
                }
            }
            String envelopeSender = envelope != null ? envelope.getMail() : null;
            return new MessageContext(session, envelope, parser,
                    session != null ? session.getFriendAddr() : null,
                    session != null ? session.getFriendRdns() : null,
                    session != null ? session.getEhlo() : null,
                    envelopeSender,
                    domainFromEmail(envelopeSender),
                    headerFrom,
                    domainFromEmail(fromEmail),
                    signatures,
                    rspamd);
        }

        String header(String name) {
            return headerValue(parser, name);
        }

        RspamdSymbol findRspamdSymbol(String prefix) {
            for (Map.Entry<String, Object> entry : rspamdSymbols().entrySet()) {
                if (entry.getKey().startsWith(prefix)) return new RspamdSymbol(entry.getKey(), entry.getValue());
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> rspamdSymbols() {
            Object value = rspamdResult.get("symbols");
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Collections.emptyMap();
        }

        Set<String> dkimDomains() {
            Set<String> domains = new LinkedHashSet<>();
            for (DkimSignature sig : dkimSignatures) {
                if (!isBlank(sig.domain())) domains.add(trimDot(sig.domain().toLowerCase(Locale.ROOT)));
            }
            return domains;
        }

        Set<String> mailDomains() {
            Set<String> domains = new LinkedHashSet<>();
            if (!isBlank(envelopeDomain)) domains.add(envelopeDomain);
            if (!isBlank(headerFromDomain)) domains.add(headerFromDomain);
            return domains;
        }

        Set<String> reputationDomains() {
            Set<String> domains = new LinkedHashSet<>(mailDomains());
            domains.addAll(dkimDomains());
            if (!isBlank(ehlo) && !ehlo.startsWith("[") && isValidSmtpDomain(trimDot(ehlo))) domains.add(trimDot(ehlo).toLowerCase(Locale.ROOT));
            if (!isBlank(rdns) && isValidSmtpDomain(trimDot(rdns))) domains.add(trimDot(rdns).toLowerCase(Locale.ROOT));
            return domains;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return null;
    }

    private static Set<String> senderAddressesForDomain(String domain, MessageContext ctx) {
        Set<String> addresses = new LinkedHashSet<>();
        if (domain.equalsIgnoreCase(domainFromEmail(ctx.envelopeSender()))) {
            addresses.add(ctx.envelopeSender().toLowerCase(Locale.ROOT));
        }
        String headerEmail = firstEmail(ctx.headerFrom());
        if (domain.equalsIgnoreCase(domainFromEmail(headerEmail))) {
            addresses.add(headerEmail.toLowerCase(Locale.ROOT));
        }
        return addresses;
    }
}
