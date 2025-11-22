package com.mimecast.robin.bots;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.mime.EmailBuilder;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.parts.TextMimePart;
import com.mimecast.robin.mx.MXResolver;
import com.mimecast.robin.mx.StrictMx;
import com.mimecast.robin.mx.assets.DnsRecord;
import com.mimecast.robin.mx.client.XBillDnsRecordClient;
import com.mimecast.robin.mx.dane.DaneChecker;
import com.mimecast.robin.mx.dane.DaneRecord;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.QueueFiles;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.scanners.rbl.RblChecker;
import com.mimecast.robin.scanners.rbl.RblResult;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.session.Session;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xbill.DNS.Address;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Email infrastructure analysis bot that performs comprehensive email security checks.
 * <p>This bot analyzes:
 * <ul>
 *   <li>DNSBL/RBL - Sender IP reputation</li>
 *   <li>rDNS - Reverse DNS lookup</li>
 *   <li>FCrDNS - Forward Confirmed Reverse DNS</li>
 *   <li>SPF - Sender Policy Framework (from Rspamd)</li>
 *   <li>DKIM - DomainKeys Identified Mail (from Rspamd and email headers)</li>
 *   <li>DMARC - Domain-based Message Authentication (from Rspamd)</li>
 *   <li>MX Records - Mail server records</li>
 *   <li>MTA-STS - Mail Transfer Agent Strict Transport Security</li>
 *   <li>DANE - DNS-Based Authentication of Named Entities</li>
 *   <li>Virus Scan - ClamAV results</li>
 *   <li>Spam Score - Rspamd analysis</li>
 * </ul>
 *
 * <p>The bot generates a comprehensive text report with all findings and queues it for delivery.
 */
public class EmailAnalysisBot implements BotProcessor {
    private static final Logger log = LogManager.getLogger(EmailAnalysisBot.class);

    // Default RBL providers to check.
    private static final List<String> DEFAULT_RBL_PROVIDERS = Arrays.asList(
            "zen.spamhaus.org",
            "bl.spamcop.net",
            "b.barracudacentral.org",
            "dnsbl.sorbs.net"
    );

    @Override
    public void process(Connection connection, EmailParser emailParser, String botAddress) {
        try {
            log.info("Processing email analysis bot for address: {} from session UID: {}",
                    botAddress, connection.getSession().getUID());

            // Determine reply address.
            String replyTo = determineReplyAddress(connection, botAddress);
            if (replyTo == null || replyTo.isEmpty()) {
                log.warn("Could not determine reply address for bot request from session UID: {}",
                        connection.getSession().getUID());
                return;
            }

            // Generate analysis report.
            String report = generateAnalysisReport(connection);

            // Queue response email.
            queueResponse(connection.getSession(), botAddress, replyTo, report);

            log.info("Successfully queued email analysis bot response to: {} from session UID: {}",
                    replyTo, connection.getSession().getUID());

        } catch (Exception e) {
            log.error("Error processing email analysis bot for address: {} from session UID: {}",
                    botAddress, connection.getSession().getUID(), e);
        }
    }

    /**
     * Determines the reply address.
     * <p>Priority order:
     * <ol>
     *   <li>Sieve reply address embedded in bot address (robotEmail+token+user+domain.com@botdomain)</li>
     *   <li>Reply-To header from envelope (extracted before parser closed)</li>
     *   <li>From header from envelope (extracted before parser closed)</li>
     *   <li>Envelope MAIL FROM</li>
     * </ol>
     *
     * @param connection SMTP connection.
     * @param botAddress The bot address that was matched.
     * @return Reply address or null if none found.
     */
    private String determineReplyAddress(Connection connection, String botAddress) {
        // Check for sieve reply address embedded in bot address.
        // Format: robot+token+user+domain.com@botdomain.com
        // The part after second + becomes user@domain.com (+ converted to @)
        Pattern sievePattern = Pattern.compile("^[^+]+\\+(.+)@[^@]+$");
        Matcher matcher = sievePattern.matcher(botAddress);
        if (matcher.matches()) {
            String afterFirstPlus = matcher.group(1);
            // Check if there's a second + (indicating reply address)
            int secondPlusIndex = afterFirstPlus.indexOf('+');
            if (secondPlusIndex >= 0 && secondPlusIndex < afterFirstPlus.length() - 1) {
                // Everything after the second + is the reply address with + instead of @
                String replyPart = afterFirstPlus.substring(secondPlusIndex + 1);
                // Convert first + to @ to form the email address
                int firstPlusInReply = replyPart.indexOf('+');
                if (firstPlusInReply >= 0) {
                    String replyAddress = replyPart.substring(0, firstPlusInReply) + "@" +
                            replyPart.substring(firstPlusInReply + 1);
                    log.debug("Using sieve reply address: {}", replyAddress);
                    return replyAddress;
                }
            }
        }

        if (!connection.getSession().getEnvelopes().isEmpty()) {
            MessageEnvelope envelope = connection.getSession().getEnvelopes().getLast();

            // Try Reply-To header first.
            String replyTo = envelope.getHeaders().get("X-Parsed-Reply-To");
            if (replyTo != null && !replyTo.isEmpty()) {
                log.debug("Using Reply-To header from envelope: {}", replyTo);
                return extractEmailAddress(replyTo);
            }

            // Try From header.
            String from = envelope.getHeaders().get("X-Parsed-From");
            if (from != null && !from.isEmpty()) {
                log.debug("Using From header from envelope: {}", from);
                return extractEmailAddress(from);
            }

            // Fall back to envelope MAIL FROM.
            String mailFrom = envelope.getMail();
            if (mailFrom != null && !mailFrom.isEmpty()) {
                log.debug("Using envelope MAIL FROM: {}", mailFrom);
                return mailFrom;
            }
        }

        return null;
    }

    /**
     * Extracts email address from a header value.
     *
     * @param headerValue Header value.
     * @return Extracted email address.
     */
    private String extractEmailAddress(String headerValue) {
        if (headerValue.contains("<") && headerValue.contains(">")) {
            int start = headerValue.indexOf('<') + 1;
            int end = headerValue.indexOf('>');
            if (start > 0 && end > start) {
                return headerValue.substring(start, end);
            }
        }
        return headerValue.trim();
    }

    /**
     * Generates comprehensive analysis report.
     *
     * @param connection SMTP connection with session data.
     * @return Analysis report as text.
     */
    private String generateAnalysisReport(Connection connection) {
        StringBuilder report = new StringBuilder();
        Session session = connection.getSession();

        report.append("=".repeat(70)).append("\n");
        report.append("EMAIL INFRASTRUCTURE ANALYSIS REPORT\n");
        report.append("Generated by Robin MTA - ").append(LocalDateTime.now()).append("\n");
        report.append("Session UID: ").append(session.getUID()).append("\n");
        report.append("=".repeat(70)).append("\n\n");

        // 1. DNSBL Check.
        appendDnsblCheck(report, session);

        // 2. rDNS Check.
        appendRdnsCheck(report, session);

        // 3. FCrDNS Check.
        appendFcrdnsCheck(report, session);

        // 4. SPF Check (from Rspamd).
        appendSpfCheck(report, session);

        // 5. DKIM Check (from Rspamd and headers).
        appendDkimCheck(report, session);

        // 6. DMARC Check (from Rspamd).
        appendDmarcCheck(report, session);

        // 7. MX Records.
        appendMxCheck(report, session);

        // 8. MTA-STS Check.
        appendMtaStsCheck(report, session);

        // 9. DANE Check.
        appendDaneCheck(report, session);

        // 10. Virus Scan Results.
        appendVirusScanResults(report, session);

        // 11. Spam Analysis.
        appendSpamAnalysis(report, session);

        report.append("\n").append("=".repeat(70)).append("\n");
        report.append("END OF REPORT\n");
        report.append("=".repeat(70)).append("\n");

        return report.toString();
    }

    /**
     * Append DNSBL check results.
     */
    private void appendDnsblCheck(StringBuilder report, Session session) {
        report.append("DNSBL Check\n");
        report.append("-".repeat(70)).append("\n");

        String senderIp = session.getFriendAddr();
        if (senderIp == null || senderIp.isEmpty()) {
            report.append("Sender IP: Not available\n\n");
            return;
        }

        report.append("Sender IP: ").append(senderIp).append("\n");

        // Check RBLs.
        List<RblResult> rblResults = RblChecker.checkIpAgainstRbls(senderIp, DEFAULT_RBL_PROVIDERS, 5);

        boolean isListed = rblResults.stream().anyMatch(RblResult::isListed);

        if (isListed) {
            report.append("Status: BLACKLISTED\n\n");
            report.append("Listed in the following RBLs:\n");
            for (RblResult result : rblResults) {
                if (result.isListed()) {
                    report.append("  - ").append(result.getRblProvider())
                            .append(" (").append(String.join(", ", result.getResponseRecords())).append(")\n");
                }
            }
        } else {
            report.append("Status: NOT BLACKLISTED\n");
            report.append("The sender IP is not listed in any checked RBLs.\n");
        }

        report.append("\n");
    }

    /**
     * Append rDNS check results.
     */
    private void appendRdnsCheck(StringBuilder report, Session session) {
        report.append("Reverse DNS (rDNS)\n");
        report.append("-".repeat(70)).append("\n");

        String senderIp = session.getFriendAddr();
        String rdns = session.getFriendRdns();

        report.append(String.format("%-20s %-50s\n", "IP", "rDNS"));
        report.append(String.format("%-20s %-50s\n",
                senderIp != null ? senderIp : "N/A",
                rdns != null ? rdns : "No rDNS"));
        report.append("\n");
    }

    /**
     * Append FCrDNS (Forward Confirmed Reverse DNS) check results.
     */
    private void appendFcrdnsCheck(StringBuilder report, Session session) {
        report.append("Forward Confirmed Reverse DNS (FCrDNS)\n");
        report.append("-".repeat(70)).append("\n");

        String senderIp = session.getFriendAddr();
        String rdns = session.getFriendRdns();

        if (senderIp == null || rdns == null || rdns.equals("unknown")) {
            report.append("Cannot perform FCrDNS check - missing rDNS\n\n");
            return;
        }

        // Perform forward lookup of rDNS.
        try {
            // Remove trailing dot if present.
            String lookupHost = rdns.endsWith(".") ? rdns.substring(0, rdns.length() - 1) : rdns;
            String forwardIp = Address.getByName(lookupHost).getHostAddress();

            boolean matches = senderIp.equals(forwardIp);

            report.append(String.format("%-20s %-30s %-20s %-10s\n", "IP", "rDNS", "Forward IP", "Result"));
            report.append(String.format("%-20s %-30s %-20s %-10s\n",
                    senderIp, rdns, forwardIp, matches ? "PASS" : "FAIL"));

            if (!matches) {
                report.append("\nWARNING: FCrDNS validation failed! Forward lookup does not match sender IP.\n");
            }
        } catch (Exception e) {
            report.append(String.format("%-20s %-30s %-20s %-10s\n", "IP", "rDNS", "Forward IP", "Result"));
            report.append(String.format("%-20s %-30s %-20s %-10s\n",
                    senderIp, rdns, "Lookup failed", "ERROR"));
            log.debug("FCrDNS lookup failed for {}: {}", rdns, e.getMessage());
        }

        report.append("\n");
    }

    /**
     * Append SPF check results from Rspamd.
     */
    private void appendSpfCheck(StringBuilder report, Session session) {
        report.append("SPF (Sender Policy Framework)\n");
        report.append("-".repeat(70)).append("\n");

        Map<String, Object> spfData = extractRspamdSymbol(session, "R_SPF");

        if (spfData.isEmpty()) {
            report.append("SPF Check: Not performed or no data available\n\n");
            return;
        }

        report.append("SPF Record: ").append(spfData.getOrDefault("description", "N/A")).append("\n");
        report.append("Result: ").append(spfData.getOrDefault("name", "None")).append("\n");
        report.append("Score: ").append(spfData.getOrDefault("score", "0.0")).append("\n");

        report.append("\n");
    }

    /**
     * Append DKIM check results.
     */
    private void appendDkimCheck(StringBuilder report, Session session) {
        report.append("DKIM (DomainKeys Identified Mail)\n");
        report.append("-".repeat(70)).append("\n");

        // Get DKIM results from Rspamd.
        Map<String, Object> dkimData = extractRspamdSymbol(session, "R_DKIM");

        if (dkimData.isEmpty()) {
            report.append("DKIM Check: Not performed or no signature found\n\n");
            return;
        }

        report.append("DKIM Signature: Found\n");
        report.append("Verification: ").append(dkimData.getOrDefault("name", "Unknown")).append("\n");
        report.append("Score: ").append(dkimData.getOrDefault("score", "0.0")).append("\n");
        report.append("Details: ").append(dkimData.getOrDefault("description", "N/A")).append("\n");

        report.append("\n");
    }

    /**
     * Append DMARC check results from Rspamd.
     */
    private void appendDmarcCheck(StringBuilder report, Session session) {
        report.append("DMARC (Domain-based Message Authentication)\n");
        report.append("-".repeat(70)).append("\n");

        Map<String, Object> dmarcData = extractRspamdSymbol(session, "DMARC");

        if (dmarcData.isEmpty()) {
            report.append("DMARC Check: Not performed\n");
            report.append("Error: No DMARC record found or DMARC not checked\n\n");
            return;
        }

        report.append("DMARC Policy: ").append(dmarcData.getOrDefault("description", "N/A")).append("\n");
        report.append("Result: ").append(dmarcData.getOrDefault("name", "None")).append("\n");
        report.append("Score: ").append(dmarcData.getOrDefault("score", "0.0")).append("\n");

        report.append("\n");
    }

    /**
     * Append MX records check.
     */
    private void appendMxCheck(StringBuilder report, Session session) {
        report.append("MX Records\n");
        report.append("-".repeat(70)).append("\n");

        // Extract domain from envelope sender.
        String domain = extractDomain(session);
        if (domain == null) {
            report.append("Cannot determine sender domain\n\n");
            return;
        }

        report.append("Domain: ").append(domain).append("\n\n");

        try {
            MXResolver resolver = new MXResolver();
            List<DnsRecord> mxRecords = resolver.resolveMx(domain);

            if (mxRecords.isEmpty()) {
                report.append("No MX records found\n");
            } else {
                report.append(String.format("%-10s %-50s\n", "Priority", "Server"));
                report.append("-".repeat(70)).append("\n");
                for (DnsRecord mx : mxRecords) {
                    report.append(String.format("%-10d %-50s\n", mx.getPriority(), mx.getValue()));
                }
            }
        } catch (Exception e) {
            report.append("Error retrieving MX records: ").append(e.getMessage()).append("\n");
            log.error("Error retrieving MX records for {}: {}", domain, e.getMessage());
        }

        report.append("\n");
    }

    /**
     * Append MTA-STS check results.
     */
    private void appendMtaStsCheck(StringBuilder report, Session session) {
        report.append("MTA-STS (Mail Transfer Agent Strict Transport Security)\n");
        report.append("-".repeat(70)).append("\n");

        String domain = extractDomain(session);
        if (domain == null) {
            report.append("Cannot determine sender domain\n\n");
            return;
        }

        report.append("Domain: ").append(domain).append("\n");

        try {
            StrictMx strictMx = new StrictMx(domain);
            var policy = strictMx.getPolicy();

            if (policy != null) {
                report.append("MTA-STS Status: ENABLED\n");
                report.append("Policy Mode: ").append(policy.getMode()).append("\n");
                report.append("Max Age: ").append(policy.getMaxAge()).append(" seconds\n");

                List<String> mxMasks = policy.getMxMasks();
                if (!mxMasks.isEmpty()) {
                    report.append("Allowed MX Masks:\n");
                    for (String mask : mxMasks) {
                        report.append("  - ").append(mask).append("\n");
                    }
                }
            } else {
                report.append("MTA-STS Status: NOT ENABLED\n");
                report.append("No MTA-STS policy found for this domain\n");
            }
        } catch (Exception e) {
            report.append("MTA-STS Status: ERROR\n");
            report.append("Error checking MTA-STS: ").append(e.getMessage()).append("\n");
            log.error("Error checking MTA-STS for {}: {}", domain, e.getMessage());
        }

        report.append("\n");
    }

    /**
     * Append DANE check results.
     */
    private void appendDaneCheck(StringBuilder report, Session session) {
        report.append("DANE (DNS-Based Authentication of Named Entities)\n");
        report.append("-".repeat(70)).append("\n");

        String domain = extractDomain(session);
        if (domain == null) {
            report.append("Cannot determine sender domain\n\n");
            return;
        }

        report.append("Domain: ").append(domain).append("\n\n");

        try {
            // Get MX records first.
            XBillDnsRecordClient dnsClient = new XBillDnsRecordClient();
            var mxRecordsOpt = dnsClient.getMxRecords(domain);

            if (mxRecordsOpt.isEmpty() || mxRecordsOpt.get().isEmpty()) {
                report.append("No MX records found - cannot check DANE\n");
            } else {
                List<String> mxHosts = mxRecordsOpt.get().stream()
                        .map(DnsRecord::getValue)
                        .toList();

                boolean anyDaneFound = false;
                for (String mxHost : mxHosts) {
                    List<DaneRecord> daneRecords = DaneChecker.checkDane(mxHost);

                    if (!daneRecords.isEmpty()) {
                        anyDaneFound = true;
                        report.append("MX Host: ").append(mxHost).append("\n");
                        report.append("DANE Status: ENABLED\n");
                        report.append("TLSA Records:\n");

                        for (DaneRecord dane : daneRecords) {
                            report.append("  - Usage: ").append(dane.getUsage())
                                    .append(" (").append(dane.getUsageDescription()).append(")\n");
                            report.append("    Selector: ").append(dane.getSelector())
                                    .append(" (").append(dane.getSelectorDescription()).append(")\n");
                            report.append("    Matching: ").append(dane.getMatchingType())
                                    .append(" (").append(dane.getMatchingTypeDescription()).append(")\n");
                            report.append("    Data: ").append(dane.getCertificateData().substring(0,
                                    Math.min(64, dane.getCertificateData().length()))).append("...\n");
                        }
                        report.append("\n");
                    }
                }

                if (!anyDaneFound) {
                    report.append("DANE Status: NOT ENABLED\n");
                    report.append("No TLSA records found for any MX hosts\n");
                }
            }
        } catch (Exception e) {
            report.append("DANE Check: ERROR\n");
            report.append("Error: ").append(e.getMessage()).append("\n");
            log.error("Error checking DANE for {}: {}", domain, e.getMessage());
        }

        report.append("\n");
    }

    /**
     * Append virus scan results from ClamAV.
     */
    private void appendVirusScanResults(StringBuilder report, Session session) {
        report.append("Virus Scan Results (ClamAV)\n");
        report.append("-".repeat(70)).append("\n");

        if (!session.getEnvelopes().isEmpty()) {
            MessageEnvelope envelope = session.getEnvelopes().getLast();
            List<Map<String, Object>> scanResults = envelope.getScanResults();

            boolean clamavFound = false;
            for (Map<String, Object> result : scanResults) {
                if ("clamav".equals(result.get("scanner"))) {
                    clamavFound = true;
                    Boolean infected = (Boolean) result.get("infected");

                    if (Boolean.TRUE.equals(infected)) {
                        report.append("Status: INFECTED\n");
                        Object viruses = result.get("viruses");
                        if (viruses != null) {
                            report.append("Viruses Found:\n");
                            report.append("  ").append(viruses.toString()).append("\n");
                        }
                    } else {
                        report.append("Status: CLEAN\n");
                        report.append("No viruses detected\n");
                    }
                }
            }

            if (!clamavFound) {
                report.append("Status: NOT SCANNED\n");
                report.append("ClamAV scan was not performed\n");
            }
        } else {
            report.append("No scan results available\n");
        }

        report.append("\n");
    }

    /**
     * Append spam analysis results from Rspamd.
     */
    private void appendSpamAnalysis(StringBuilder report, Session session) {
        report.append("Spam Analysis (Rspamd)\n");
        report.append("-".repeat(70)).append("\n");

        if (!session.getEnvelopes().isEmpty()) {
            MessageEnvelope envelope = session.getEnvelopes().getLast();
            List<Map<String, Object>> scanResults = envelope.getScanResults();

            boolean rspamdFound = false;
            for (Map<String, Object> result : scanResults) {
                if ("rspamd".equals(result.get("scanner"))) {
                    rspamdFound = true;
                    Double score = (Double) result.get("score");
                    Boolean spam = (Boolean) result.get("spam");

                    report.append("Spam Score: ").append(score != null ? score : "0.0").append("\n");
                    report.append("Spam Status: ").append(Boolean.TRUE.equals(spam) ? "SPAM" : "NOT SPAM").append("\n\n");

                    Object symbols = result.get("symbols");
                    if (symbols instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> symbolMap = (Map<String, Object>) symbols;
                        if (!symbolMap.isEmpty()) {
                            report.append("Triggered Rules:\n");
                            report.append(String.format("%-8s %-40s\n", "Score", "Rule Name"));
                            report.append("-".repeat(70)).append("\n");

                            symbolMap.forEach((key, value) -> {
                                double symbolScore = value instanceof Number ? ((Number) value).doubleValue() : 0.0;
                                report.append(String.format("%-8.2f %-40s\n", symbolScore, key));
                            });
                        }
                    }
                }
            }

            if (!rspamdFound) {
                report.append("Status: NOT ANALYZED\n");
                report.append("Rspamd analysis was not performed\n");
            }
        } else {
            report.append("No analysis results available\n");
        }

        report.append("\n");
    }

    /**
     * Extract a specific symbol from Rspamd results.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractRspamdSymbol(Session session, String symbolPrefix) {
        if (!session.getEnvelopes().isEmpty()) {
            MessageEnvelope envelope = session.getEnvelopes().getLast();
            List<Map<String, Object>> scanResults = envelope.getScanResults();

            for (Map<String, Object> result : scanResults) {
                if ("rspamd".equals(result.get("scanner"))) {
                    Object symbols = result.get("symbols");
                    if (symbols instanceof Map) {
                        Map<String, Object> symbolMap = (Map<String, Object>) symbols;
                        for (Map.Entry<String, Object> entry : symbolMap.entrySet()) {
                            if (entry.getKey().startsWith(symbolPrefix)) {
                                Map<String, Object> data = new HashMap<>();
                                data.put("name", entry.getKey());
                                data.put("score", entry.getValue());
                                data.put("description", entry.getKey());
                                return data;
                            }
                        }
                    }
                }
            }
        }
        return Collections.emptyMap();
    }

    /**
     * Extract domain from envelope sender.
     */
    private String extractDomain(Session session) {
        if (!session.getEnvelopes().isEmpty()) {
            String mailFrom = session.getEnvelopes().getLast().getMail();
            if (mailFrom != null && mailFrom.contains("@")) {
                return mailFrom.substring(mailFrom.indexOf("@") + 1);
            }
        }
        return null;
    }

    /**
     * Queues the response email for delivery.
     */
    private void queueResponse(Session session, String botAddress, String replyTo, String report) {
        try {
            // Create envelope for response.
            MessageEnvelope envelope = new MessageEnvelope();
            envelope.setMail(stripBotAddress(botAddress));
            envelope.setRcpt(replyTo);
            envelope.setSubject("Email Infrastructure Analysis Report - " + session.getUID());

            // Create outbound session for delivery.
            Session outboundSession = new Session();
            outboundSession.setDirection(EmailDirection.OUTBOUND);
            outboundSession.setEhlo(Config.getServer().getHostname());
            outboundSession.getEnvelopes().add(envelope);

            // Build MIME email with report.
            ByteArrayOutputStream emailStream = new ByteArrayOutputStream();
            new EmailBuilder(outboundSession, envelope)
                    .addHeader("Subject", envelope.getSubject())
                    .addHeader("To", replyTo)
                    .addHeader("From", envelope.getMail())
                    .addPart(new TextMimePart(report.getBytes(StandardCharsets.UTF_8))
                            .addHeader("Content-Type", "text/plain; charset=\"UTF-8\"")
                            .addHeader("Content-Transfer-Encoding", "8bit")
                    )
                    .writeTo(emailStream);

            // Write email to .eml file in store folder.
            String emlFilePath = writeEmailToStore(emailStream, outboundSession.getUID());

            // Set the file path on the envelope.
            envelope.setFile(emlFilePath);

            // Create relay session and queue.
            RelaySession relaySession = new RelaySession(outboundSession);
            relaySession.setProtocol("ESMTP");

            // Persist envelope files to queue folder.
            QueueFiles.persistEnvelopeFiles(relaySession);

            // Queue for delivery.
            PersistentQueue.getInstance().enqueue(relaySession);

            log.info("Queued email analysis bot response for delivery to: {}", replyTo);

        } catch (IOException e) {
            log.error("Failed to queue email analysis bot response: {}", e.getMessage(), e);
        }
    }

    /**
     * Strips token from bot address.
     */
    private String stripBotAddress(String botAddress) {
        if (botAddress == null || !botAddress.contains("+")) {
            return botAddress;
        }

        int firstPlusIndex = botAddress.indexOf('+');
        int atIndex = botAddress.lastIndexOf('@');

        if (firstPlusIndex != -1 && atIndex != -1 && firstPlusIndex < atIndex) {
            String prefix = botAddress.substring(0, firstPlusIndex);
            String domain = botAddress.substring(atIndex);
            return prefix + domain;
        }

        return botAddress;
    }

    /**
     * Writes the email to an .eml file in the store folder.
     */
    private String writeEmailToStore(ByteArrayOutputStream emailStream, String sessionUid) throws IOException {
        String basePath = Config.getServer().getStorage().getStringProperty("path", "/tmp/store");
        Path storePath = Paths.get(basePath);
        Files.createDirectories(storePath);

        String filename = String.format("%s-%s.eml",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")),
                sessionUid);
        Path emailFile = storePath.resolve(filename);

        try (FileOutputStream fos = new FileOutputStream(emailFile.toFile())) {
            emailStream.writeTo(fos);
            log.debug("Wrote bot response email to store: {}", emailFile);
        }

        return emailFile.toString();
    }

    @Override
    public String getName() {
        return "email";
    }
}
