package com.mimecast.robin.bots;

import com.google.common.net.InternetDomainName;
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
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
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

        // Ensure email is parsed before creating context (parser may be unparsed from LocalStorageClient)
        if (emailParser != null && emailParser.getHeaders().get().isEmpty()) {
            try {
                emailParser.parse();
            } catch (Exception e) {
                log.warn("Failed to parse email for analysis: {}", e.getMessage());
            }
        }

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
            report.add(checkSpfRecord(ctx));
        }
        if (cfg.isDkimCheckEnabled()) {
            report.add(checkDkim(ctx));
            report.addAll(checkDkimSelectors(ctx));
        }
        if (cfg.isDmarcCheckEnabled()) {
            report.add(checkDmarc(ctx));
            report.add(checkDmarcRecord(ctx));
            report.add(checkDmarcAlignment(ctx));
        }
        if (cfg.isAuthenticationResultsCheckEnabled()) {
            report.add(checkAuthenticationResults(ctx));
        }
        if (cfg.isEaiCheckEnabled()) {
            report.add(checkEai(ctx));
        }
        if (cfg.isRequireTlsCheckEnabled()) {
            report.add(checkRequireTls(ctx));
        }
        if (cfg.isArcDkim2AdvisoryEnabled()) {
            report.add(checkArcDkim2Advisory(ctx));
        }
        if (cfg.isMxCheckEnabled()) {
            Set<String> bimiDomains = new LinkedHashSet<>();
            for (String domain : ctx.mailDomains()) {
                if (cfg.isSoaCheckEnabled()) {
                    report.add(checkAuthoritativeDns(domain, cfg));
                }
                report.addAll(checkMxDomain(domain, ctx, cfg));
                if (cfg.isBimiCheckEnabled()) {
                    effectiveOrganizationalDomain(domain).ifPresent(bimiDomains::add);
                }
            }
            for (String domain : bimiDomains) {
                report.add(checkBimi(domain));
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
        Set<String> checkedDomains = new LinkedHashSet<>();
        for (String domain : ctx.ptrReputationDomains()) {
            if (checkedDomains.add(domain)) {
                checks.add(checkDblDomain("PTR reputation: " + domain, domain, cfg));
            }
        }
        for (String domain : ctx.sendingReputationDomains()) {
            if (checkedDomains.add(domain)) {
                checks.add(checkDblDomain("Domain reputation: " + domain, domain, cfg));
            }
        }
        if (checks.isEmpty()) {
            checks.add(CheckResult.builder(Category.REPUTATION, "Domain reputation")
                    .status(Status.SKIPPED)
                    .summary("No message domains were available for DBL checks.")
                    .build());
        }
        return checks;
    }

    private CheckResult checkDblDomain(String checkName, String domain, EmailAnalysisBotConfig cfg) {
        CheckResult.Builder b = CheckResult.builder(Category.REPUTATION, checkName)
                .reference("DBL/SURBL operational reputation check")
                .evidence("Domain", domain);
        List<DblResult> results = DblChecker.checkDomainAgainstDbls(domain, cfg.getDblProviders(),
                cfg.getDblTimeoutSeconds());
        boolean listed = results.stream().anyMatch(DblResult::isListed);
        for (DblResult result : results) {
            b.evidence(result.getDblProvider(),
                    result.isListed() ? "LISTED " + result.getResponseRecords() : "clear");
        }
        return b.status(listed ? Status.FAIL : Status.PASS)
                .summary(listed ? "Domain is listed by at least one configured DBL." :
                        "Domain was not listed by configured DBLs.")
                .remediation(listed ? "Review the listed domain reputation result and remove abusive content or URLs." : null)
                .build();
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

    private CheckResult checkSpfRecord(MessageContext ctx) {
        String domain = ctx.envelopeDomain();
        CheckResult.Builder b = CheckResult.builder(Category.DNS_PUBLISHING, "SPF DNS record")
                .reference("RFC 7208")
                .evidence("Envelope sender domain", nvl(domain, "N/A"));
        if (isBlank(domain)) {
            return b.status(Status.SKIPPED).summary("No envelope sender domain was available.").build();
        }
        List<String> txt = txtRecords(domain);
        List<String> spf = txt.stream()
                .filter(v -> v.toLowerCase(Locale.ROOT).startsWith("v=spf1"))
                .toList();
        b.evidence("SPF TXT", spf.isEmpty() ? "none" : String.join(" | ", spf));
        if (spf.isEmpty()) {
            return b.status(Status.WARN)
                    .summary("No SPF record was found.")
                    .remediation("Publish " + domain + " TXT with v=spf1 policy.")
                    .build();
        }
        if (spf.size() > 1) {
            return b.status(Status.FAIL)
                    .summary("Multiple SPF records were found.")
                    .remediation("Publish exactly one SPF TXT record; multiple records cause PermError.")
                    .build();
        }
        String record = spf.getFirst();
        if (record.contains("+all")) {
            return b.status(Status.FAIL)
                    .summary("SPF contains +all which authorizes any sender.")
                    .remediation("Replace +all with ~all or -all.")
                    .build();
        }
        if (record.contains("?all")) {
            return b.status(Status.WARN)
                    .summary("SPF uses ?all (neutral) which provides weak protection.")
                    .remediation("Consider using ~all or -all for stronger protection.")
                    .build();
        }
        SpfAnalysis analysis = analyzeSpf(domain, record, new LinkedHashSet<>(), new ArrayList<>());
        b.evidence("DNS lookup count", String.valueOf(analysis.lookupCount()))
                .evidence("SPF chain", analysis.chain().isEmpty() ? "none" : String.join(" -> ", analysis.chain()))
                .evidence("Void lookups", String.valueOf(analysis.voidLookups()));
        if (!analysis.problems().isEmpty()) {
            return b.status(Status.FAIL)
                    .summary("SPF record has syntax or DNS lookup-budget issues.")
                    .evidence("Problems", String.join("; ", analysis.problems()))
                    .remediation("Fix malformed SPF terms and keep DNS-consuming mechanisms within RFC 7208 limits.")
                    .build();
        }
        if (analysis.lookupCount() > 10) {
            return b.status(Status.FAIL)
                    .summary("SPF exceeds the 10-DNS-lookup limit.")
                    .remediation("Flatten or remove SPF include, redirect, a, mx, ptr, and exists mechanisms.")
                    .build();
        }
        if (analysis.lookupCount() >= 8) {
            return b.status(Status.WARN)
                    .summary("SPF is close to the 10-DNS-lookup limit.")
                    .remediation("Reduce DNS-consuming SPF mechanisms before future includes push the record over the limit.")
                    .build();
        }
        return b.status(Status.PASS)
                .summary("A single SPF record was found.")
                .build();
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
                    .reference("RFC 8301")
                    .reference("RFC 8463")
                    .evidence("Signing domain", sig.domain())
                    .evidence("Selector", sig.selector())
                    .evidence("Algorithm", nvl(sig.algorithm(), "N/A"))
                    .evidence("Canonicalization", nvl(sig.canonicalization(), "N/A"))
                    .evidence("Signed headers", nvl(sig.signedHeaders(), "N/A"))
                    .evidence("Body hash", nvl(sig.bodyHash(), "N/A"));
            if (isBlank(sig.domain()) || isBlank(sig.selector())) {
                checks.add(b.status(Status.FAIL)
                        .summary("DKIM signature is missing d= or s=.")
                        .remediation("Emit DKIM signatures with both domain and selector tags.")
                        .build());
                continue;
            }
            boolean fromSigned = signedHeaderContains(sig.signedHeaders(), "from");
            b.evidence("From header signed", yesNo(fromSigned))
                    .evidence("Common mutable headers signed", commonSignedHeaders(sig.signedHeaders()));
            String name = sig.selector() + "._domainkey." + sig.domain();
            List<String> txt = txtRecords(name);
            b.evidence("TXT records", txt.isEmpty() ? "none" : String.join(" | ", txt));
            if (txt.isEmpty()) {
                checks.add(b.status(Status.FAIL)
                        .summary("DKIM selector TXT record was not found.")
                        .remediation("Publish the public key at " + name + ".")
                        .build());
            } else if (sig.algorithm() != null && sig.algorithm().toLowerCase(Locale.ROOT).contains("sha1")) {
                checks.add(b.status(Status.FAIL)
                        .summary("DKIM signature uses SHA-1.")
                        .remediation("Use a modern DKIM signing algorithm such as rsa-sha256 or ed25519-sha256.")
                        .build());
            } else {
                DkimKeyAssessment key = assessDkimKey(txt);
                b.evidence("Key algorithm", nvl(key.algorithm(), "N/A"))
                        .evidence("Key size", key.keyBits() > 0 ? key.keyBits() + " bits" : "N/A");
                Status status = highest(Status.PASS, key.status(), fromSigned ? Status.PASS : Status.WARN);
                checks.add(b.status(status)
                        .summary(dkimSelectorSummary(status, fromSigned, key))
                        .remediation(status == Status.PASS ? null :
                                "Publish a valid modern DKIM key and sign the From header.")
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
        DmarcPolicy policy = discoverDmarcPolicy(domain);
        b.reference("RFC 9989")
                .reference("RFC 9990")
                .reference("RFC 9991")
                .evidence("Policy domain", nvl(policy.policyDomain(), "none"))
                .evidence("Organizational domain", nvl(policy.organizationalDomain(), "N/A"))
                .evidence("DNS tree walk", String.join(" -> ", policy.queries()))
                .evidence("_dmarc TXT", isBlank(policy.record()) ? "none" : policy.record());
        if (isBlank(policy.record())) {
            return b.status(Status.WARN)
                    .summary("No DMARC record was found.")
                    .remediation("Publish _dmarc." + domain + " TXT with at least v=DMARC1; p=none.")
                    .build();
        }
        if (policy.multipleRecords()) {
            return b.status(Status.FAIL)
                    .summary("Multiple DMARC records were found.")
                    .remediation("Publish exactly one DMARC TXT record.")
                    .build();
        }
        Map<String, String> tags = policy.tags();
        b.evidence("Policy", nvl(tags.get("p"), "missing"))
                .evidence("Subdomain policy", nvl(tags.get("sp"), "not set"))
                .evidence("Non-existent domain policy", nvl(tags.get("np"), "not set"))
                .evidence("PSD flag", nvl(tags.get("psd"), "not set"))
                .evidence("Testing flag", nvl(tags.get("t"), "not set"))
                .evidence("Aggregate reports", nvl(tags.get("rua"), "not set"))
                .evidence("Failure reports", nvl(tags.get("ruf"), "not set"));
        if (!policy.errors().isEmpty()) {
            return b.status(Status.FAIL)
                    .summary("DMARC record has syntax issues.")
                    .evidence("Problems", String.join("; ", policy.errors()))
                    .remediation("Fix malformed or duplicate DMARC tags before relying on the policy.")
                    .build();
        }
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

    private CheckResult checkDmarcAlignment(MessageContext ctx) {
        DmarcPolicy policy = discoverDmarcPolicy(ctx.headerFromDomain());
        String aspf = policy.tags().getOrDefault("aspf", "r");
        String adkim = policy.tags().getOrDefault("adkim", "r");
        String spfDomain = !isBlank(ctx.envelopeDomain()) ? ctx.envelopeDomain() :
                (!isBlank(ctx.ehlo()) && !ctx.ehlo().startsWith("[") ? trimDot(ctx.ehlo()) : null);
        boolean spfAligned = aligned(spfDomain, ctx.headerFromDomain(), aspf, policy);
        List<String> dkimAlignment = new ArrayList<>();
        boolean dkimAligned = false;
        for (String dkimDomain : ctx.dkimDomains()) {
            boolean ok = aligned(dkimDomain, ctx.headerFromDomain(), adkim, policy);
            dkimAligned |= ok;
            dkimAlignment.add(dkimDomain + "=" + (ok ? "aligned" : "not aligned"));
        }

        CheckResult.Builder b = CheckResult.builder(Category.AUTHENTICATION, "DMARC identifier alignment")
                .reference("RFC 9989")
                .evidence("Header From domain", nvl(ctx.headerFromDomain(), "N/A"))
                .evidence("Policy domain", nvl(policy.policyDomain(), "none"))
                .evidence("SPF domain", nvl(spfDomain, "N/A"))
                .evidence("SPF alignment mode", alignmentMode(aspf))
                .evidence("SPF aligned", yesNo(spfAligned))
                .evidence("DKIM domains", dkimAlignment.isEmpty() ? "none" : String.join(", ", dkimAlignment))
                .evidence("DKIM alignment mode", alignmentMode(adkim))
                .evidence("DKIM aligned", yesNo(dkimAligned));
        if (isBlank(ctx.headerFromDomain())) {
            return b.status(Status.SKIPPED).summary("No header From domain was available.").build();
        }
        if (spfAligned || dkimAligned) {
            return b.status(Status.PASS).summary("At least one authenticated identifier aligns with the From domain.").build();
        }
        return b.status(Status.INFO)
                .summary("No SPF or DKIM identifier alignment was visible in parsed message evidence.")
                .build();
    }

    private CheckResult checkAuthenticationResults(MessageContext ctx) {
        List<String> headers = ctx.headers("Authentication-Results");
        CheckResult.Builder b = CheckResult.builder(Category.AUTHENTICATION, "Authentication-Results headers")
                .reference("RFC 8601")
                .evidence("Header count", String.valueOf(headers.size()));
        if (headers.isEmpty()) {
            return b.status(Status.SKIPPED).summary("No Authentication-Results headers were present.").build();
        }
        int index = 1;
        for (String header : headers) {
            b.evidence("Authentication-Results " + index++, summarizeAuthenticationResults(header));
        }
        return b.status(Status.INFO)
                .summary("Authentication-Results headers are present as upstream evidence; trust depends on the receiving boundary.")
                .build();
    }

    private CheckResult checkEai(MessageContext ctx) {
        boolean needsEai = containsNonAscii(ctx.envelopeSender()) || containsNonAscii(ctx.headerFrom()) ||
                ctx.headers("To").stream().anyMatch(EmailAnalysisBot::containsNonAscii) ||
                ctx.headers("Cc").stream().anyMatch(EmailAnalysisBot::containsNonAscii);
        CheckResult.Builder b = CheckResult.builder(Category.AUTHENTICATION, "Internationalized email")
                .reference("RFC 6531")
                .reference("RFC 8616")
                .evidence("Envelope sender", nvl(ctx.envelopeSender(), "N/A"))
                .evidence("Header From", nvl(ctx.headerFrom(), "N/A"))
                .evidence("Requires SMTPUTF8", yesNo(needsEai))
                .evidence("Normalized mail domains", formatSet(ctx.mailDomains()));
        return b.status(needsEai ? Status.INFO : Status.SKIPPED)
                .summary(needsEai ? "Message contains internationalized address or header evidence." :
                        "Message does not appear to require SMTPUTF8 handling.")
                .build();
    }

    private CheckResult checkRequireTls(MessageContext ctx) {
        List<String> headers = ctx.headers("TLS-Required");
        CheckResult.Builder b = CheckResult.builder(Category.TRANSPORT_SECURITY, "TLS-Required header")
                .reference("RFC 8689")
                .evidence("Header count", String.valueOf(headers.size()));
        if (headers.isEmpty()) {
            return b.status(Status.SKIPPED).summary("No TLS-Required header was present.").build();
        }
        b.evidence("TLS-Required", String.join(" | ", headers));
        boolean validSingleNo = headers.size() == 1 && "No".equalsIgnoreCase(headers.getFirst().trim());
        return b.status(validSingleNo ? Status.INFO : Status.WARN)
                .summary(validSingleNo ? "TLS-Required explicitly asks relays not to require policy-based TLS." :
                        "TLS-Required header is repeated or malformed.")
                .remediation(validSingleNo ? null : "Use at most one TLS-Required header, with the value No.")
                .build();
    }

    private CheckResult checkArcDkim2Advisory(MessageContext ctx) {
        List<String> aar = ctx.headers("ARC-Authentication-Results");
        List<String> ams = ctx.headers("ARC-Message-Signature");
        List<String> seal = ctx.headers("ARC-Seal");
        RspamdSymbol arc = ctx.findRspamdSymbol("ARC");
        CheckResult.Builder b = CheckResult.builder(Category.AUTHENTICATION, "ARC / DKIM2 advisory")
                .reference("RFC 8617")
                .evidence("ARC-Authentication-Results", String.valueOf(aar.size()))
                .evidence("ARC-Message-Signature", String.valueOf(ams.size()))
                .evidence("ARC-Seal", String.valueOf(seal.size()))
                .evidence("DKIM2", "active Internet-Draft; not a runtime validation target");
        if (arc != null) {
            b.evidence("Rspamd ARC symbol", arc.name()).evidence("Details", arc.details());
        }
        return b.status(Status.INFO)
                .summary("ARC and DKIM2 are advisory context only and do not affect the DMARC conclusion.")
                .build();
    }

    private CheckResult checkAuthoritativeDns(String domain, EmailAnalysisBotConfig cfg) {
        CheckResult.Builder b = CheckResult.builder(Category.DNS_PUBLISHING, "Authoritative DNS: " + domain)
                .reference("RFC 1034")
                .reference("RFC 1035")
                .evidence("Domain", domain);
        if (isBlank(domain)) {
            return b.status(Status.SKIPPED).summary("No domain was available for authoritative DNS checks.").build();
        }
        Optional<String> zone = findAuthoritativeZone(domain);
        if (zone.isEmpty()) {
            return b.status(Status.FAIL)
                    .summary("No enclosing authoritative DNS zone with SOA was found.")
                    .remediation("Publish a valid DNS zone with NS and SOA records.")
                    .build();
        }
        b.evidence("Discovered zone", zone.get());
        Record[] nsRecords = lookupRecords(zone.get(), Type.NS);
        if (nsRecords.length == 0) {
            return b.status(Status.FAIL)
                    .summary("Authoritative zone has no NS records.")
                    .remediation("Publish NS records for the authoritative zone.")
                    .build();
        }
        int authoritative = 0;
        long serial = -1;
        for (Record record : nsRecords) {
            if (!(record instanceof NSRecord ns)) continue;
            String nsHost = trimDot(ns.getTarget().toString(true));
            String evidence = querySoaAtNameserver(zone.get(), nsHost, cfg.getSoaCheckTimeoutSeconds());
            if (evidence.contains("AA=yes")) {
                authoritative++;
            }
            Matcher serialMatcher = Pattern.compile("serial=(\\d+)").matcher(evidence);
            if (serialMatcher.find()) {
                long nextSerial = Long.parseLong(serialMatcher.group(1));
                if (serial < 0) {
                    serial = nextSerial;
                } else if (serial != nextSerial) {
                    evidence += "; serial-mismatch";
                }
            }
            b.evidence(nsHost, evidence);
        }
        if (authoritative == nsRecords.length) {
            return b.status(Status.PASS).summary("All nameservers answered authoritatively for the discovered zone.").build();
        }
        if (authoritative > 0) {
            return b.status(Status.WARN)
                    .summary("Some nameservers did not answer authoritatively for the discovered zone.")
                    .remediation("Fix lame or inconsistent nameserver delegation.")
                    .build();
        }
        return b.status(Status.FAIL)
                .summary("No nameserver answered authoritatively for the discovered zone.")
                .remediation("Fix the domain's NS delegation and authoritative zone service.")
                .build();
    }

    private CheckResult checkBimi(String domain) {
        DmarcPolicy dmarc = discoverDmarcPolicy(domain);
        List<String> txt = txtRecords("default._bimi." + domain);
        List<String> bimi = txt.stream()
                .filter(v -> v.toLowerCase(Locale.ROOT).startsWith("v=bimi1"))
                .toList();
        CheckResult.Builder b = CheckResult.builder(Category.DNS_PUBLISHING, "BIMI: " + domain)
                .reference("BIMI ecosystem specification")
                .evidence("Domain", domain)
                .evidence("BIMI TXT", bimi.isEmpty() ? "none" : String.join(" | ", bimi))
                .evidence("DMARC enforcement", yesNo(isDmarcEnforced(dmarc)));
        if (bimi.isEmpty()) {
            return b.status(Status.INFO).summary("No BIMI assertion record was found.").build();
        }
        if (bimi.size() > 1) {
            return b.status(Status.WARN)
                    .summary("Multiple BIMI assertion records were found.")
                    .remediation("Publish exactly one BIMI TXT record at default._bimi." + domain + ".")
                    .build();
        }
        TagParse tags = parseTagList(bimi.getFirst(), true);
        b.evidence("Logo URL", nvl(tags.tags().get("l"), "missing"))
                .evidence("Evidence URL", nvl(tags.tags().get("a"), "not set"));
        List<String> problems = new ArrayList<>(tags.errors());
        String logo = tags.tags().get("l");
        String evidence = tags.tags().get("a");
        if (isBlank(logo) || !isHttpsUri(logo)) problems.add("l must be an HTTPS SVG URL");
        if (!isBlank(evidence) && !isHttpsUri(evidence)) problems.add("a must be an HTTPS certificate URL when present");
        if (!isDmarcEnforced(dmarc)) problems.add("DMARC must be at quarantine or reject enforcement for BIMI display");
        if (!problems.isEmpty()) {
            return b.status(Status.WARN)
                    .summary("BIMI record was found, but prerequisites or syntax need attention.")
                    .evidence("Problems", String.join("; ", problems))
                    .remediation("Fix the BIMI record and enforce DMARC before expecting mailbox-provider display.")
                    .build();
        }
        return b.status(Status.PASS).summary("BIMI record and DMARC enforcement prerequisite are present.").build();
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
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            checks.add(mxBuilder.status(Status.ERROR)
                    .summary("MX lookup failed: " + errorMsg)
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

        // Check if this is implicit MX fallback (domain itself as MX, typically priority 0 or 10)
        // Implicit fallback via CNAME is acceptable - RFC 2181 restriction is about explicit MX pointing to CNAME
        boolean isImplicitFallback = mxRecords.size() == 1 &&
                trimDot(mxRecords.getFirst().getValue()).equalsIgnoreCase(trimDot(domain));

        Set<String> checkedMxIps = new LinkedHashSet<>();
        for (DnsRecord mx : mxRecords) {
            String hostValue = mx.getValue();
            if (hostValue == null || hostValue.isEmpty()) {
                continue;
            }
            String host = trimDot(hostValue);
            boolean cname = hasRecord(host, Type.CNAME);
            Set<String> addresses = resolveHostAddresses(host);
            mxBuilder.evidence(mx.getPriority() + " " + host,
                    (addresses.isEmpty() ? "no A/AAAA" : formatSet(addresses)) +
                    (cname ? (isImplicitFallback ? "; CNAME (implicit fallback)" : "; CNAME target") : ""));
            PortTlsResult tlsResult = probeMxPort(host, cfg);
            checks.add(checkMxPort(domain, host, tlsResult));
            if (cfg.isCertStrengthCheckEnabled()) {
                checks.add(checkMxCertificateStrength(domain, host, tlsResult));
            }
            if (cfg.isMxPtrCheckEnabled()) {
                checks.add(checkMxPtrAlignment(domain, host, addresses));
            }
            if (cfg.isMxRblCheckEnabled()) {
                for (String address : addresses) {
                    if (checkedMxIps.add(address)) {
                        checks.add(checkMxReputation(domain, host, address, cfg));
                    }
                }
            }
            if (cfg.isRecipientProbeEnabled()) {
                checks.addAll(checkSenderAddresses(domain, host, ctx, cfg));
                checks.addAll(checkRoleAddresses(domain, host, cfg));
            }
        }

        // RFC 2181 §10.3: MX record target must not be a CNAME
        // BUT: This only applies to explicit MX records, not implicit RFC 5321 fallback
        // Implicit fallback uses A/AAAA for the domain itself - CNAME chains are acceptable there
        boolean anyBadTarget = mxRecords.stream()
                .map(r -> trimDot(r.getValue() != null ? r.getValue() : ""))
                .filter(host -> !host.isEmpty())
                .anyMatch(host -> {
                    // CNAME is only a violation for explicit MX records, not implicit fallback
                    boolean hasCname = hasRecord(host, Type.CNAME);
                    boolean cnameViolation = hasCname && !isImplicitFallback;
                    return cnameViolation || resolveHostAddresses(host).isEmpty() || isIpLiteral(host);
                });
        checks.addFirst(mxBuilder.status(anyBadTarget ? Status.FAIL : Status.PASS)
                .summary(anyBadTarget ? "At least one MX target is not RFC-correct." :
                        (isImplicitFallback ? "Domain uses RFC 5321 implicit MX fallback." :
                                "MX targets resolve to address records and are not CNAME aliases."))
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

    private PortTlsResult probeMxPort(String host, EmailAnalysisBotConfig cfg) {
        List<PortTlsResult> results = PortTlsChecker.checkPorts(host, List.of(25),
                cfg.getPortCheckTimeoutSeconds(), cfg.getProbeEhloName());
        return results.isEmpty() ? null : results.getFirst();
    }

    private CheckResult checkMxPort(String domain, String host, PortTlsResult result) {
        CheckResult.Builder b = CheckResult.builder(Category.MX_RECEIVING, "MX SMTP port 25: " + host)
                .reference("RFC 5321")
                .reference("RFC 3207")
                .reference("RFC 6531")
                .reference("RFC 8689")
                .evidence("Domain", domain)
                .evidence("MX host", host);
        if (result == null) {
            return b.status(Status.ERROR).summary("Port probe did not return a result.").build();
        }
        b.evidence("Open", yesNo(result.isOpen()))
                .evidence("STARTTLS/TLS", result.getTlsStatus().name())
                .evidence("Server greeting", nvl(result.getBanner(), "N/A"))
                .evidence("EHLO extensions", result.getExtensions().isEmpty() ? "none" : String.join(", ", result.getExtensions()))
                .evidence("SMTPUTF8", yesNo(hasExtension(result, "SMTPUTF8")))
                .evidence("REQUIRETLS", yesNo(hasExtension(result, "REQUIRETLS")))
                .evidence("Certificate expiry", result.getCertExpiry() != null ? result.getCertExpiry().toString() : "N/A")
                .evidence("Certificate subject", nvl(result.getCertSubject(), "N/A"))
                .evidence("Certificate issuer", nvl(result.getCertIssuer(), "N/A"));
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

    private CheckResult checkMxCertificateStrength(String domain, String host, PortTlsResult result) {
        CheckResult.Builder b = CheckResult.builder(Category.TRANSPORT_SECURITY, "MX TLS certificate: " + host)
                .reference("RFC 3207")
                .reference("RFC 8461")
                .reference("RFC 8996")
                .reference("RFC 8446")
                .evidence("Domain", domain)
                .evidence("MX host", host);
        if (result == null || !result.isOpen()) {
            return b.status(Status.SKIPPED).summary("MX port 25 was not reachable.").build();
        }
        b.evidence("TLS status", result.getTlsStatus().name())
                .evidence("Negotiated protocol", nvl(result.getNegotiatedProtocol(), "N/A"))
                .evidence("Hostname matches certificate", yesNo(result.isHostnameMatch()))
                .evidence("Certificate subject", nvl(result.getCertSubject(), "N/A"))
                .evidence("Certificate issuer", nvl(result.getCertIssuer(), "N/A"))
                .evidence("Certificate key size", result.getCertKeyBits() > 0 ? result.getCertKeyBits() + " bits" : "N/A");
        if (result.getTlsStatus() != PortTlsResult.TlsStatus.ENABLED) {
            return b.status(Status.WARN)
                    .summary("MX did not complete STARTTLS during probing.")
                    .remediation("Enable STARTTLS with a valid certificate on the advertised MX host.")
                    .build();
        }
        if (isLegacyTls(result.getNegotiatedProtocol())) {
            return b.status(Status.FAIL)
                    .summary("MX negotiated a deprecated TLS protocol.")
                    .remediation("Disable SSLv3, TLS 1.0, and TLS 1.1; support TLS 1.2 or TLS 1.3.")
                    .build();
        }
        if (result.getCertKeyBits() > 0 && result.getCertKeyBits() < 2048) {
            return b.status(Status.WARN)
                    .summary("MX TLS certificate uses a small public key.")
                    .remediation("Use a certificate with at least a 2048-bit RSA key or modern equivalent.")
                    .build();
        }
        if (!result.isHostnameMatch()) {
            return b.status(Status.WARN)
                    .summary("MX TLS certificate does not match the MX hostname.")
                    .remediation("Use a certificate whose SAN covers the advertised MX hostname.")
                    .build();
        }
        return b.status(Status.PASS).summary("MX TLS certificate negotiated with modern parameters.").build();
    }

    private CheckResult checkMxReputation(String domain, String host, String ip, EmailAnalysisBotConfig cfg) {
        CheckResult.Builder b = CheckResult.builder(Category.REPUTATION, "MX host reputation: " + ip)
                .reference("DNSBL operational reputation check")
                .evidence("Domain", domain)
                .evidence("MX host", host)
                .evidence("MX IP", ip);
        if (!isIpv4(ip)) {
            return b.status(Status.SKIPPED)
                    .summary("MX IP reputation checks currently query IPv4 DNSBL zones only.")
                    .build();
        }
        List<RblResult> results = RblChecker.checkIpAgainstRbls(ip, cfg.getRblProviders(), cfg.getRblTimeoutSeconds());
        boolean listed = results.stream().anyMatch(RblResult::isListed);
        for (RblResult result : results) {
            b.evidence(result.getRblProvider(),
                    result.isListed() ? "LISTED " + result.getResponseRecords() : "clear");
        }
        return b.status(listed ? Status.FAIL : Status.PASS)
                .summary(listed ? "The MX IP is listed by at least one DNSBL." :
                        "The MX IP was not listed by configured DNSBLs.")
                .remediation(listed ? "Review the listed DNSBL result and remediate MX host reputation issues." : null)
                .build();
    }

    private CheckResult checkMxPtrAlignment(String domain, String host, Set<String> addresses) {
        CheckResult.Builder b = CheckResult.builder(Category.MX_RECEIVING, "MX PTR alignment: " + host)
                .reference("RFC 5321 4.1.4")
                .evidence("Domain", domain)
                .evidence("MX host", host)
                .evidence("MX addresses", formatSet(addresses));
        if (addresses == null || addresses.isEmpty()) {
            return b.status(Status.SKIPPED).summary("MX host has no resolved address records.").build();
        }
        XBillDnsRecordClient dns = new XBillDnsRecordClient();
        boolean anyConfirmed = false;
        boolean anyExact = false;
        for (String address : addresses) {
            Optional<String> ptr = dns.getPtrRecord(address);
            String ptrName = ptr.map(EmailAnalysisBot::trimDot).orElse("none");
            Set<String> ptrAddresses = ptr.map(EmailAnalysisBot::resolveHostAddresses).orElse(Collections.emptySet());
            boolean confirmed = ptrAddresses.contains(address);
            boolean exact = ptr.isPresent() && trimDot(ptr.get()).equalsIgnoreCase(host);
            anyConfirmed |= confirmed;
            anyExact |= exact;
            b.evidence(address + " PTR", ptrName + "; forward-confirmed=" + yesNo(confirmed));
        }
        if (anyExact && anyConfirmed) {
            return b.status(Status.PASS).summary("At least one MX PTR exactly matches and forward-confirms the MX host.").build();
        }
        if (anyConfirmed) {
            return b.status(Status.WARN)
                    .summary("MX PTR is forward-confirmed, but does not exactly match the MX hostname.")
                    .remediation("For self-hosted MX infrastructure, align PTR names with the advertised MX hostname where practical.")
                    .build();
        }
        return b.status(Status.WARN)
                .summary("MX PTR records are missing or not forward-confirmed.")
                .remediation("Configure reverse DNS and forward-confirmation for MX host addresses.")
                .build();
    }

    private List<CheckResult> checkRoleAddresses(String domain, String host, EmailAnalysisBotConfig cfg) {
        List<CheckResult> checks = new ArrayList<>();

        // Skip role address checks for bounce/tracking subdomains - these are infrastructure domains
        // that route through intermediary servers, not final destination domains.
        // RFC 5321 postmaster requirement applies to domains that accept mail for final delivery.
        String lowerDomain = domain.toLowerCase(Locale.ROOT);
        if (lowerDomain.startsWith("bounce.") || lowerDomain.startsWith("track.") ||
                lowerDomain.startsWith("click.") || lowerDomain.startsWith("link.") ||
                lowerDomain.startsWith("return.") || lowerDomain.startsWith("reply.")) {
            return checks; // Skip - bounce domains don't need postmaster
        }

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
            List<PortTlsResult> results = PortTlsChecker.checkPorts(host, cfg.getPortCheckPorts(),
                    cfg.getPortCheckTimeoutSeconds(), cfg.getProbeEhloName());
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
            if (mxRecords.isEmpty()) {
                return b.status(Status.INFO)
                        .summary("No MX records found for DANE check.")
                        .build();
            }
            boolean anyDane = false;
            for (DnsRecord mx : mxRecords) {
                String mxHost = mx.getValue();
                if (mxHost == null || mxHost.isEmpty()) {
                    continue;
                }
                List<DaneRecord> tlsa = DaneChecker.checkDane(mxHost);
                anyDane |= !tlsa.isEmpty();
                b.evidence(trimDot(mxHost), tlsa.isEmpty() ? "no TLSA" : tlsa.size() + " TLSA record(s)");
            }
            return b.status(anyDane ? Status.PASS : Status.INFO)
                    .summary(anyDane ? "At least one MX publishes TLSA records." :
                            "No TLSA records were found for MX hosts.")
                    .remediation(anyDane ? "Ensure DNSSEC validation is working; DANE depends on secure DNS." : null)
                    .build();
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return b.status(Status.ERROR).summary("DANE check failed: " + errorMsg).build();
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
        String allHeaders = ctx.allHeaders();
        if (!allHeaders.isEmpty()) {
            b.evidence("All headers", "\n" + allHeaders);
        }
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
        return normalizeDomain(domain);
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
        return new DkimSignature(normalizeDomain(tags.get("d")), tags.get("s"), tags.get("a"),
                tags.get("c"), tags.get("h"), tags.get("bh"));
    }

    private static SpfAnalysis analyzeSpf(String domain, String record, Set<String> seen, List<String> chain) {
        List<String> problems = new ArrayList<>();
        if (isBlank(domain) || isBlank(record)) {
            return new SpfAnalysis(0, 0, List.copyOf(chain), List.of("missing SPF record"));
        }
        String normalized = normalizeDomain(domain);
        if (normalized == null || !seen.add(normalized)) {
            return new SpfAnalysis(0, 0, List.copyOf(chain), List.of("recursive include or redirect for " + domain));
        }
        chain.add(normalized);
        int lookupCount = 0;
        int voidLookups = 0;
        for (String rawTerm : record.split("\\s+")) {
            String term = rawTerm.trim();
            if (term.isEmpty() || term.equalsIgnoreCase("v=spf1")) continue;
            char first = term.charAt(0);
            if (first == '+' || first == '-' || first == '~' || first == '?') {
                term = term.substring(1);
            }
            String mechanism = spfMechanismName(term);
            String value;
            if (term.contains(":")) {
                value = term.substring(term.indexOf(':') + 1);
            } else if (term.contains("=")) {
                value = term.substring(term.indexOf('=') + 1);
            } else {
                value = normalized;
            }
            value = value.contains("/") ? value.substring(0, value.indexOf('/')) : value;
            if ("include".equals(mechanism)) {
                lookupCount++;
                List<String> includes = spfRecords(value);
                if (includes.isEmpty()) {
                    voidLookups++;
                } else {
                    SpfAnalysis nested = analyzeSpf(value, includes.getFirst(), seen, chain);
                    lookupCount += nested.lookupCount();
                    voidLookups += nested.voidLookups();
                    problems.addAll(nested.problems());
                }
            } else if ("redirect".equals(mechanism)) {
                lookupCount++;
                List<String> redirects = spfRecords(value);
                if (redirects.isEmpty()) {
                    voidLookups++;
                } else {
                    SpfAnalysis nested = analyzeSpf(value, redirects.getFirst(), seen, chain);
                    lookupCount += nested.lookupCount();
                    voidLookups += nested.voidLookups();
                    problems.addAll(nested.problems());
                }
            } else if ("a".equals(mechanism) || "exists".equals(mechanism) || "ptr".equals(mechanism)) {
                lookupCount++;
                int type = "exists".equals(mechanism) ? Type.A : Type.A;
                if (lookupRecords(value, type).length == 0) voidLookups++;
                if ("ptr".equals(mechanism)) problems.add("ptr mechanism is slow and discouraged");
            } else if ("mx".equals(mechanism)) {
                lookupCount++;
                Record[] mxRecords = lookupRecords(value, Type.MX);
                if (mxRecords.length == 0) {
                    voidLookups++;
                }
                for (Record mxRecord : mxRecords) {
                    if (mxRecord instanceof org.xbill.DNS.MXRecord mx) {
                        lookupCount++;
                        String mxHost = trimDot(mx.getTarget().toString(true));
                        if (lookupRecords(mxHost, Type.A).length == 0 && lookupRecords(mxHost, Type.AAAA).length == 0) {
                            voidLookups++;
                        }
                    }
                }
            } else if (term.contains("=") && !Set.of("redirect", "exp").contains(mechanism)) {
                problems.add("unknown SPF modifier: " + rawTerm);
            } else if (!Set.of("all", "ip4", "ip6", "exp").contains(mechanism)) {
                problems.add("unknown SPF mechanism: " + rawTerm);
            }
        }
        return new SpfAnalysis(lookupCount, voidLookups, List.copyOf(chain), List.copyOf(problems));
    }

    private static String spfMechanismName(String term) {
        int colon = term.indexOf(':');
        int slash = term.indexOf('/');
        int equals = term.indexOf('=');
        int end = term.length();
        for (int idx : List.of(colon, slash, equals)) {
            if (idx >= 0) end = Math.min(end, idx);
        }
        return term.substring(0, end).toLowerCase(Locale.ROOT);
    }

    private static List<String> spfRecords(String domain) {
        return txtRecords(domain).stream()
                .filter(v -> v.toLowerCase(Locale.ROOT).startsWith("v=spf1"))
                .toList();
    }

    private static DkimKeyAssessment assessDkimKey(List<String> txt) {
        if (txt == null || txt.isEmpty()) {
            return new DkimKeyAssessment(Status.FAIL, null, 0, List.of("missing DKIM selector record"));
        }
        List<String> problems = new ArrayList<>();
        Status status = Status.PASS;
        String algorithm = null;
        int bits = 0;
        List<String> dkim = txt.stream()
                .filter(v -> v.toLowerCase(Locale.ROOT).startsWith("v=dkim1"))
                .toList();
        if (dkim.size() > 1) {
            return new DkimKeyAssessment(Status.FAIL, null, 0, List.of("multiple DKIM selector records"));
        }
        Map<String, String> tags = tagMap(dkim.isEmpty() ? txt.getFirst() : dkim.getFirst());
        algorithm = tags.getOrDefault("k", "rsa").toLowerCase(Locale.ROOT);
        String key = tags.get("p");
        if (isBlank(key)) {
            return new DkimKeyAssessment(Status.FAIL, algorithm, 0, List.of("empty or revoked DKIM public key"));
        }
        if ("rsa".equals(algorithm)) {
            try {
                RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(key)));
                bits = publicKey.getModulus().bitLength();
                if (bits < 1024) {
                    problems.add("RSA key is below the RFC 8301 minimum of 1024 bits");
                    status = Status.FAIL;
                } else if (bits < 2048) {
                    problems.add("RSA key is below the RFC 8301 2048-bit recommendation");
                    status = Status.WARN;
                }
            } catch (Exception e) {
                problems.add("RSA key could not be parsed");
                status = Status.WARN;
            }
        } else if ("ed25519".equals(algorithm)) {
            bits = 256;
        } else {
            problems.add("unknown DKIM key algorithm: " + algorithm);
            status = Status.WARN;
        }
        return new DkimKeyAssessment(status, algorithm, bits, List.copyOf(problems));
    }

    private static boolean signedHeaderContains(String signedHeaders, String headerName) {
        if (isBlank(signedHeaders) || isBlank(headerName)) return false;
        for (String header : signedHeaders.split(":")) {
            if (headerName.equalsIgnoreCase(header.trim())) return true;
        }
        return false;
    }

    private static String commonSignedHeaders(String signedHeaders) {
        if (isBlank(signedHeaders)) return "none";
        List<String> important = new ArrayList<>();
        for (String name : List.of("subject", "date", "message-id", "to", "cc", "reply-to", "mime-version", "content-type")) {
            if (signedHeaderContains(signedHeaders, name)) important.add(name);
        }
        return important.isEmpty() ? "none" : String.join(", ", important);
    }

    private static String dkimSelectorSummary(Status status, boolean fromSigned, DkimKeyAssessment key) {
        if (status == Status.PASS) return "DKIM selector TXT record exists and uses acceptable key material.";
        List<String> issues = new ArrayList<>(key.problems());
        if (!fromSigned) issues.add("From header is not signed");
        return issues.isEmpty() ? "DKIM selector needs attention." : String.join("; ", issues) + ".";
    }

    private static DmarcPolicy discoverDmarcPolicy(String domain) {
        String normalized = normalizeDomain(domain);
        if (normalized == null) return DmarcPolicy.empty(domain, List.of());
        List<String> queries = new ArrayList<>();
        List<String> candidates = new ArrayList<>();
        candidates.add(normalized);
        effectiveOrganizationalDomain(normalized).ifPresent(org -> {
            String cursor = normalized;
            while (cursor.contains(".") && !cursor.equalsIgnoreCase(org)) {
                cursor = cursor.substring(cursor.indexOf('.') + 1);
                if (!candidates.contains(cursor)) candidates.add(cursor);
            }
            if (!candidates.contains(org)) candidates.add(org);
        });
        if (candidates.size() == 1 && normalized.contains(".")) {
            String parent = normalized.substring(normalized.indexOf('.') + 1);
            if (!candidates.contains(parent)) candidates.add(parent);
        }
        for (String candidate : candidates) {
            String name = "_dmarc." + candidate;
            queries.add(name);
            List<String> dmarc = txtRecords(name).stream()
                    .filter(v -> v.toLowerCase(Locale.ROOT).startsWith("v=dmarc1"))
                    .toList();
            if (dmarc.isEmpty()) continue;
            TagParse parsed = parseDmarcTags(dmarc.getFirst());
            return new DmarcPolicy(candidate, organizationalDomainFromPolicy(normalized, candidate),
                    dmarc.getFirst(), parsed.tags(), List.copyOf(queries), parsed.errors(), dmarc.size() > 1);
        }
        return DmarcPolicy.empty(normalized, queries);
    }

    private static TagParse parseDmarcTags(String record) {
        Map<String, String> tags = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<String> errors = new ArrayList<>();
        for (String part : nullToEmpty(record).split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            String[] kv = trimmed.split("=", 2);
            if (kv.length != 2 || kv[0].isBlank()) {
                errors.add("malformed tag: " + trimmed);
                continue;
            }
            String key = kv[0].trim().toLowerCase(Locale.ROOT);
            String value = kv[1].trim();
            if (tags.containsKey(key)) {
                errors.add("duplicate tag: " + key);
                continue;
            }
            tags.put(key, value);
        }
        if (!"DMARC1".equalsIgnoreCase(tags.get("v"))) errors.add("missing v=DMARC1");
        for (String key : List.of("p", "sp", "np")) {
            String value = tags.get(key);
            if (value != null && !Set.of("none", "quarantine", "reject").contains(value.toLowerCase(Locale.ROOT))) {
                errors.add("invalid " + key + "=" + value);
            }
        }
        for (String key : List.of("adkim", "aspf")) {
            String value = tags.get(key);
            if (value != null && !Set.of("r", "s").contains(value.toLowerCase(Locale.ROOT))) {
                errors.add("invalid " + key + "=" + value);
            }
        }
        if (tags.containsKey("pct")) {
            try {
                int pct = Integer.parseInt(tags.get("pct"));
                if (pct < 0 || pct > 100) errors.add("pct must be between 0 and 100");
            } catch (NumberFormatException e) {
                errors.add("pct must be an integer");
            }
        }
        for (String uri : parseTagList(tags.get("rua"))) {
            if (!isMailtoUri(uri)) errors.add("rua must contain mailto URIs: " + uri);
        }
        for (String uri : parseTagList(tags.get("ruf"))) {
            if (!isMailtoUri(uri)) errors.add("ruf must contain mailto URIs: " + uri);
        }
        return new TagParse(Collections.unmodifiableMap(tags), List.copyOf(errors));
    }

    private static List<String> parseTagList(String value) {
        if (isBlank(value)) return List.of();
        List<String> values = new ArrayList<>();
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) values.add(trimmed);
        }
        return values;
    }

    private static TagParse parseTagList(String record, boolean requireVersion) {
        Map<String, String> tags = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<String> errors = new ArrayList<>();
        for (String part : nullToEmpty(record).split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            String[] kv = trimmed.split("=", 2);
            if (kv.length != 2 || kv[0].isBlank()) {
                errors.add("malformed tag: " + trimmed);
                continue;
            }
            String key = kv[0].trim().toLowerCase(Locale.ROOT);
            if (tags.containsKey(key)) {
                errors.add("duplicate tag: " + key);
                continue;
            }
            tags.put(key, kv[1].trim());
        }
        if (requireVersion && !"BIMI1".equalsIgnoreCase(tags.get("v"))) {
            errors.add("missing v=BIMI1");
        }
        return new TagParse(Collections.unmodifiableMap(tags), List.copyOf(errors));
    }

    private static boolean aligned(String authenticatedDomain, String headerFromDomain, String mode, DmarcPolicy policy) {
        String auth = normalizeDomain(authenticatedDomain);
        String from = normalizeDomain(headerFromDomain);
        if (auth == null || from == null) return false;
        if ("s".equalsIgnoreCase(mode)) return auth.equalsIgnoreCase(from);
        String authOrg = effectiveOrganizationalDomain(auth).orElse(auth);
        String fromOrg = !isBlank(policy.organizationalDomain()) ? policy.organizationalDomain() :
                effectiveOrganizationalDomain(from).orElse(from);
        return authOrg.equalsIgnoreCase(fromOrg);
    }

    private static String organizationalDomainFromPolicy(String originalDomain, String policyDomain) {
        return effectiveOrganizationalDomain(originalDomain)
                .or(() -> effectiveOrganizationalDomain(policyDomain))
                .orElse(policyDomain);
    }

    private static Optional<String> effectiveOrganizationalDomain(String domain) {
        if (isBlank(domain)) return Optional.empty();
        try {
            InternetDomainName name = InternetDomainName.from(IDN.toASCII(trimDot(domain).toLowerCase(Locale.ROOT)));
            if (name.hasPublicSuffix()) return Optional.of(name.topPrivateDomain().toString());
        } catch (IllegalArgumentException ignored) {
            // Fall through to the conservative two-label fallback.
        }
        String normalized = normalizeDomain(domain);
        if (normalized == null || !normalized.contains(".")) return Optional.empty();
        String[] labels = normalized.split("\\.");
        return Optional.of(labels[labels.length - 2] + "." + labels[labels.length - 1]);
    }

    private static String alignmentMode(String mode) {
        return "s".equalsIgnoreCase(mode) ? "strict" : "relaxed";
    }

    private static Status highest(Status... statuses) {
        Status worst = Status.PASS;
        for (Status status : statuses) {
            if (severity(status) > severity(worst)) worst = status;
        }
        return worst;
    }

    private static int severity(Status status) {
        return switch (status) {
            case FAIL -> 5;
            case ERROR -> 4;
            case WARN -> 3;
            case INFO -> 2;
            case SKIPPED -> 1;
            case PASS -> 0;
        };
    }

    private static String summarizeAuthenticationResults(String header) {
        String compact = nullToEmpty(header).replaceAll("\\s+", " ").trim();
        return compact.length() <= 240 ? compact : compact.substring(0, 237) + "...";
    }

    private static boolean containsNonAscii(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7f) return true;
        }
        return false;
    }

    private static boolean isLegacyTls(String protocol) {
        return "TLSv1".equalsIgnoreCase(protocol) || "TLSv1.1".equalsIgnoreCase(protocol) ||
                "SSLv3".equalsIgnoreCase(protocol);
    }

    private static boolean hasExtension(PortTlsResult result, String extension) {
        if (result == null || result.getExtensions().isEmpty()) return false;
        for (String value : result.getExtensions()) {
            String token = value.split("\\s+", 2)[0];
            if (extension.equalsIgnoreCase(token)) return true;
        }
        return false;
    }

    private static boolean isIpv4(String value) {
        if (isBlank(value)) return false;
        try {
            return InetAddress.getByName(value) instanceof Inet4Address;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isHttpsUri(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isMailtoUri(String value) {
        try {
            URI uri = URI.create(value);
            return "mailto".equalsIgnoreCase(uri.getScheme());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normalizeDomain(String domain) {
        if (isBlank(domain)) return null;
        String trimmed = trimDot(domain).toLowerCase(Locale.ROOT);
        try {
            String ascii = IDN.toASCII(trimmed);
            return ascii.isBlank() ? null : ascii;
        } catch (IllegalArgumentException e) {
            return trimmed.isBlank() ? null : trimmed;
        }
    }

    private static boolean isDmarcEnforced(DmarcPolicy policy) {
        if (policy == null || policy.tags().isEmpty()) return false;
        String p = policy.tags().get("p");
        return "quarantine".equalsIgnoreCase(p) || "reject".equalsIgnoreCase(p);
    }

    private static Optional<String> findAuthoritativeZone(String domain) {
        String cursor = normalizeDomain(domain);
        while (!isBlank(cursor)) {
            if (lookupRecords(cursor, Type.SOA).length > 0) return Optional.of(cursor);
            int dot = cursor.indexOf('.');
            if (dot < 0) break;
            cursor = cursor.substring(dot + 1);
        }
        return Optional.empty();
    }

    private static String querySoaAtNameserver(String zone, String nameserver, int timeoutSeconds) {
        try {
            SimpleResolver resolver = new SimpleResolver(nameserver);
            resolver.setTimeout(java.time.Duration.ofSeconds(Math.max(1, timeoutSeconds)));
            Message query = Message.newQuery(Record.newRecord(Name.fromString(zone + "."),
                    Type.SOA, org.xbill.DNS.DClass.IN));
            Message response = resolver.send(query);
            boolean aa = response.getHeader().getFlag(Flags.AA);
            String serial = "none";
            for (Record record : response.getSectionArray(Section.ANSWER)) {
                if (record instanceof SOARecord soa) {
                    serial = String.valueOf(soa.getSerial());
                    break;
                }
            }
            return "AA=" + yesNo(aa) + "; serial=" + serial;
        } catch (Exception e) {
            return "error=" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static Record[] lookupRecords(String name, int type) {
        try {
            Record[] records = new Lookup(name, type).run();
            return records == null ? new Record[0] : records;
        } catch (Exception e) {
            return new Record[0];
        }
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

    private static Optional<String> apexDomain(String domain) {
        if (isBlank(domain)) return Optional.empty();
        try {
            InternetDomainName name = InternetDomainName.from(IDN.toASCII(trimDot(domain).toLowerCase(Locale.ROOT)));
            if (!name.hasPublicSuffix() || name.isTopPrivateDomain()) {
                return Optional.empty();
            }
            return Optional.of(name.topPrivateDomain().toString());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
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

    record SpfAnalysis(int lookupCount, int voidLookups, List<String> chain, List<String> problems) {
    }

    record DkimSignature(String domain, String selector, String algorithm, String canonicalization,
                         String signedHeaders, String bodyHash) {
    }

    record DkimKeyAssessment(Status status, String algorithm, int keyBits, List<String> problems) {
    }

    record TagParse(Map<String, String> tags, List<String> errors) {
    }

    record DmarcPolicy(String policyDomain, String organizationalDomain, String record,
                       Map<String, String> tags, List<String> queries, List<String> errors,
                       boolean multipleRecords) {
        static DmarcPolicy empty(String domain, List<String> queries) {
            String normalized = normalizeDomain(domain);
            String organizational = effectiveOrganizationalDomain(normalized).orElse(normalized);
            return new DmarcPolicy(null, organizational, null, Map.of(),
                    List.copyOf(queries == null ? List.of() : queries), List.of(), false);
        }
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

        List<String> headers(String name) {
            if (parser == null || isBlank(name)) return List.of();
            List<String> values = new ArrayList<>();
            for (MimeHeader header : parser.getHeaders().get()) {
                if (header.getName().equalsIgnoreCase(name)) values.add(header.getValue());
            }
            return List.copyOf(values);
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
                if (!isBlank(sig.domain())) domains.add(normalizeDomain(sig.domain()));
            }
            return domains;
        }

        /**
         * Returns all message headers as a formatted string for display.
         */
        String allHeaders() {
            if (parser == null) return "";
            StringBuilder sb = new StringBuilder();
            for (MimeHeader header : parser.getHeaders().get()) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(header.getName()).append(": ").append(header.getValue());
            }
            return sb.toString();
        }

        Set<String> mailDomains() {
            Set<String> domains = new LinkedHashSet<>();
            if (!isBlank(envelopeDomain)) domains.add(envelopeDomain);
            if (!isBlank(headerFromDomain)) domains.add(headerFromDomain);
            return domains;
        }

        Set<String> ptrReputationDomains() {
            Set<String> domains = new LinkedHashSet<>();
            if (!isBlank(rdns) && isValidSmtpDomain(trimDot(rdns))) {
                String ptrDomain = trimDot(rdns).toLowerCase(Locale.ROOT);
                domains.add(ptrDomain);
                apexDomain(ptrDomain).ifPresent(domains::add);
            }
            return domains;
        }

        Set<String> sendingReputationDomains() {
            Set<String> domains = new LinkedHashSet<>(mailDomains());
            domains.addAll(dkimDomains());
            if (!isBlank(ehlo) && !ehlo.startsWith("[") && isValidSmtpDomain(trimDot(ehlo))) domains.add(trimDot(ehlo).toLowerCase(Locale.ROOT));
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
