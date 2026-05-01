package com.mimecast.robin.bots;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mimecast.robin.config.server.BotConfig;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.mime.parts.FileMimePart;
import com.mimecast.robin.mime.parts.MimePart;
import com.mimecast.robin.smtp.connection.Connection;
import okhttp3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AFRF (Authentication Failure Reporting Format) forensic report processing bot.
 * <p>Parses forensic reports (RFC 6591) from multipart/report emails and sends them
 * to the robin-admin API for storage and visualization.
 * <p>These are individual email failure reports for DMARC, DKIM, or SPF authentication
 * failures, as opposed to aggregate reports.
 * <p>Expected MIME structure:
 * <ul>
 *   <li>Part 1: text/plain - Human readable summary</li>
 *   <li>Part 2: message/feedback-report - Machine readable report fields</li>
 *   <li>Part 3: message/rfc822 or text/rfc822-headers - Original email or headers</li>
 * </ul>
 */
public class ForensicBot implements BotProcessor {
    private static final Logger log = LogManager.getLogger(ForensicBot.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Standard HTTP client for verified TLS.
    private static final OkHttpClient httpClient = new OkHttpClient();

    // Insecure HTTP client for self-signed certificates.
    private static final OkHttpClient insecureHttpClient = createInsecureClient();

    /**
     * Creates an OkHttpClient that trusts all certificates.
     * Used for development with self-signed certificates.
     */
    private static OkHttpClient createInsecureClient() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());

            // Wrap the socket factory to ensure SNI is properly set
            javax.net.ssl.SSLSocketFactory baseFactory = sslContext.getSocketFactory();
            javax.net.ssl.SSLSocketFactory sniFactory = new javax.net.ssl.SSLSocketFactory() {
                @Override
                public String[] getDefaultCipherSuites() {
                    return baseFactory.getDefaultCipherSuites();
                }

                @Override
                public String[] getSupportedCipherSuites() {
                    return baseFactory.getSupportedCipherSuites();
                }

                @Override
                public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose) throws IOException {
                    javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) baseFactory.createSocket(s, host, port, autoClose);
                    javax.net.ssl.SSLParameters params = socket.getSSLParameters();
                    params.setServerNames(List.of(new javax.net.ssl.SNIHostName(host)));
                    socket.setSSLParameters(params);
                    return socket;
                }

                @Override
                public java.net.Socket createSocket(String host, int port) throws IOException {
                    return baseFactory.createSocket(host, port);
                }

                @Override
                public java.net.Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort) throws IOException {
                    return baseFactory.createSocket(host, port, localHost, localPort);
                }

                @Override
                public java.net.Socket createSocket(java.net.InetAddress host, int port) throws IOException {
                    return baseFactory.createSocket(host, port);
                }

                @Override
                public java.net.Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort) throws IOException {
                    return baseFactory.createSocket(address, port, localAddress, localPort);
                }
            };

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sniFactory, (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to create insecure HTTP client, falling back to default", e);
            return httpClient;
        }
    }

    @Override
    public String getName() {
        return "forensic";
    }

    @Override
    public void process(Connection connection, EmailParser emailParser, String botAddress, BotConfig.BotDefinition botDefinition) {
        log.debug("Forensic bot processing email for address: {}", botAddress);

        if (emailParser == null) {
            log.warn("Forensic bot received null email parser, cannot process");
            return;
        }

        // Get endpoint from bot config.
        String endpoint = botDefinition != null ? botDefinition.getEndpoint() : "";
        boolean insecure = botDefinition != null && botDefinition.isInsecure();

        if (endpoint.isEmpty()) {
            log.warn("Forensic bot has no endpoint configured, cannot send report");
            return;
        }

        try {
            // Parse the full email to extract parts.
            emailParser.parse();

            // Find and parse the feedback-report part.
            Map<String, Object> report = extractForensicReport(emailParser);
            if (report == null || report.isEmpty()) {
                log.warn("No forensic report found in email");
                return;
            }

            // Send to robin-admin API.
            sendToAdminApi(report, endpoint, insecure);

            log.info("Successfully processed forensic report from domain: {}", report.get("reportedDomain"));

        } catch (Exception e) {
            log.error("Error processing forensic bot for session UID: {}",
                    connection.getSession().getUID(), e);
        }
    }

    /**
     * Extracts forensic report from email parts.
     *
     * @param emailParser Parsed email.
     * @return Report data map, or null if not found.
     */
    private Map<String, Object> extractForensicReport(EmailParser emailParser) {
        List<MimePart> parts = emailParser.getParts();
        log.debug("Found {} MIME parts in email", parts.size());

        Map<String, Object> report = new HashMap<>();
        String originalHeaders = null;

        for (MimePart part : parts) {
            String contentType = getContentType(part);
            log.debug("Part content-type: {}", contentType);
            if (contentType == null) continue;

            try {
                if (contentType.contains("message/feedback-report")) {
                    // Parse the feedback-report part
                    parseFeedbackReport(part, report);
                } else if (contentType.contains("text/rfc822-headers") ||
                        contentType.contains("message/rfc822")) {
                    // Extract original headers
                    byte[] content = getPartContent(part);
                    originalHeaders = new String(content);
                }
            } catch (IOException e) {
                log.warn("Failed to extract content from part: {}", e.getMessage());
            }
        }

        // Add original headers if found
        if (originalHeaders != null) {
            report.put("originalHeaders", originalHeaders);

            // Try to extract subject from original headers
            String subject = extractHeaderValue(originalHeaders, "Subject");
            if (subject != null) {
                report.put("subject", subject);
            }
        }

        return report.isEmpty() ? null : report;
    }

    /**
     * Parses the message/feedback-report part into a map.
     *
     * @param part   The MIME part containing the feedback report.
     * @param report Map to populate with parsed fields.
     */
    private void parseFeedbackReport(MimePart part, Map<String, Object> report) throws IOException {
        byte[] content = getPartContent(part);
        String reportText = new String(content);

        try (BufferedReader reader = new BufferedReader(new StringReader(reportText))) {
            String line;
            List<String> reportedUris = new ArrayList<>();

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                int colonIdx = line.indexOf(':');
                if (colonIdx <= 0) continue;

                String key = line.substring(0, colonIdx).trim().toLowerCase();
                String value = line.substring(colonIdx + 1).trim();

                switch (key) {
                    case "feedback-type":
                        report.put("feedbackType", value);
                        break;
                    case "user-agent":
                        report.put("userAgent", value);
                        break;
                    case "version":
                        report.put("version", value);
                        break;
                    case "original-mail-from":
                        report.put("originalMailFrom", cleanEmailAddress(value));
                        break;
                    case "original-rcpt-to":
                        report.put("originalRcptTo", cleanEmailAddress(value));
                        break;
                    case "received-date":
                        report.put("receivedDate", value);
                        break;
                    case "source-ip":
                        report.put("sourceIp", value);
                        break;
                    case "reported-domain":
                        report.put("reportedDomain", value);
                        break;
                    case "auth-failure":
                        report.put("authFailure", value);
                        break;
                    case "authentication-results":
                        report.put("authenticationResults", value);
                        break;
                    case "delivery-result":
                        report.put("deliveryResult", value);
                        break;
                    case "incidents":
                        try {
                            report.put("incidents", Integer.parseInt(value));
                        } catch (NumberFormatException e) {
                            report.put("incidents", 1);
                        }
                        break;
                    case "reported-uri":
                        reportedUris.add(value);
                        break;
                    case "original-envelope-id":
                        report.put("originalEnvelopeId", value);
                        break;
                    case "dkim-domain":
                        report.put("dkimDomain", value);
                        break;
                    case "dkim-selector":
                        report.put("dkimSelector", value);
                        break;
                    case "dkim-identity":
                        report.put("dkimIdentity", value);
                        break;
                    case "spf-dns":
                        report.put("spfDns", value);
                        break;
                    case "arrival-date":
                        report.put("arrivalDate", value);
                        break;
                }
            }

            if (!reportedUris.isEmpty()) {
                report.put("reportedUri", reportedUris);
            }
        }
    }

    /**
     * Cleans an email address by removing angle brackets.
     */
    private String cleanEmailAddress(String email) {
        if (email == null) return null;
        email = email.trim();
        if (email.startsWith("<") && email.endsWith(">")) {
            return email.substring(1, email.length() - 1);
        }
        return email;
    }

    /**
     * Extracts a header value from raw headers text.
     */
    private String extractHeaderValue(String headers, String headerName) {
        String lowerHeaders = headers.toLowerCase();
        String lowerName = headerName.toLowerCase() + ":";
        int idx = lowerHeaders.indexOf(lowerName);
        if (idx >= 0) {
            int start = idx + lowerName.length();
            int end = headers.indexOf('\n', start);
            if (end < 0) end = headers.length();
            return headers.substring(start, end).trim();
        }
        return null;
    }

    /**
     * Gets content type from MIME part headers.
     */
    private String getContentType(MimePart part) {
        MimeHeader ctHeader = part.getHeader("Content-Type");
        return ctHeader != null ? ctHeader.getValue().toLowerCase() : null;
    }

    /**
     * Gets content bytes from a MIME part.
     */
    private byte[] getPartContent(MimePart part) throws IOException {
        if (part instanceof FileMimePart filePart) {
            File file = filePart.getFile();
            if (file != null && file.exists()) {
                return Files.readAllBytes(file.toPath());
            }
        }
        return part.getBytes();
    }

    /**
     * Sends forensic report to the robin-admin API.
     *
     * @param report   Parsed forensic report.
     * @param endpoint API endpoint URL.
     * @param insecure Whether to skip TLS verification.
     */
    private void sendToAdminApi(Map<String, Object> report, String endpoint, boolean insecure) {
        String json = gson.toJson(report);
        log.debug("Sending forensic report to {}: {}", endpoint, json);

        RequestBody body = RequestBody.create(json, JSON);
        Request request = new Request.Builder()
                .url(endpoint)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();

        OkHttpClient client = insecure ? insecureHttpClient : httpClient;

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String responseBody = response.body() != null ? response.body().string() : "no body";
                log.error("Failed to send forensic report to robin-admin. Status: {} Response: {}",
                        response.code(), responseBody);
            } else {
                log.debug("Successfully sent forensic report to robin-admin");
            }
        } catch (IOException e) {
            log.error("Error sending forensic report to robin-admin: {}", e.getMessage(), e);
        }
    }
}
