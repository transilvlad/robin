package com.mimecast.robin.bots;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * TLSRPT (TLS Reporting) processing bot.
 * <p>Parses TLSRPT reports (RFC 8460) from email attachments and sends them
 * to the robin-admin API for storage and visualization.
 * <p>Supported attachment formats:
 * <ul>
 *   <li>application/tlsrpt+gzip - GZIP compressed JSON report</li>
 *   <li>application/tlsrpt+json - Plain JSON report</li>
 *   <li>application/gzip - GZIP compressed JSON report</li>
 *   <li>application/json - Plain JSON report</li>
 * </ul>
 */
public class TlsrptBot implements BotProcessor {
    private static final Logger log = LogManager.getLogger(TlsrptBot.class);
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
        return "tlsrpt";
    }

    @Override
    public void process(Connection connection, EmailParser emailParser, String botAddress, BotConfig.BotDefinition botDefinition) {
        log.debug("TLSRPT bot processing email for address: {}", botAddress);

        if (emailParser == null) {
            log.warn("TLSRPT bot received null email parser, cannot process");
            return;
        }

        // Get endpoint from bot config.
        String endpoint = botDefinition != null ? botDefinition.getEndpoint() : "";
        boolean insecure = botDefinition != null && botDefinition.isInsecure();

        if (endpoint.isEmpty()) {
            log.warn("TLSRPT bot has no endpoint configured, cannot send report");
            return;
        }

        try {
            // Parse the full email to extract attachments.
            emailParser.parse();

            // Find TLSRPT report attachment.
            String jsonContent = extractTlsrptReport(emailParser);
            if (jsonContent == null) {
                log.warn("No TLSRPT report attachment found in email");
                return;
            }

            // Parse and validate JSON report.
            JsonObject report = JsonParser.parseString(jsonContent).getAsJsonObject();
            if (!report.has("policies")) {
                log.warn("Invalid TLSRPT report: missing policies field");
                return;
            }

            // Send to robin-admin API.
            sendToAdminApi(report, endpoint, insecure);

            String reportId = report.has("report-id") ? report.get("report-id").getAsString() : "unknown";
            log.info("Successfully processed TLSRPT report: {}", reportId);

        } catch (Exception e) {
            log.error("Error processing TLSRPT bot for session UID: {}",
                    connection.getSession().getUID(), e);
        }
    }

    /**
     * Extracts TLSRPT report JSON from email attachments.
     *
     * @param emailParser Parsed email.
     * @return JSON content as string, or null if not found.
     */
    private String extractTlsrptReport(EmailParser emailParser) {
        List<MimePart> parts = emailParser.getParts();
        log.debug("Found {} MIME parts in email", parts.size());

        for (MimePart part : parts) {
            String contentType = getContentType(part);
            log.debug("Part content-type: {}", contentType);
            if (contentType == null) continue;

            String filename = getFilename(part);
            log.debug("Processing attachment with content-type: {} filename: {}", contentType, filename);

            try {
                // Check by content-type first
                if (contentType.contains("application/tlsrpt+gzip") ||
                        contentType.contains("application/gzip") ||
                        contentType.contains("application/x-gzip")) {
                    return extractFromGzip(part);
                } else if (contentType.contains("application/tlsrpt+json") ||
                        contentType.contains("application/json")) {
                    byte[] content = getPartContent(part);
                    return new String(content);
                }

                // For octet-stream, check filename extension
                if (contentType.contains("application/octet-stream") && filename != null) {
                    String lowerFilename = filename.toLowerCase();
                    if (lowerFilename.endsWith(".gz") || lowerFilename.endsWith(".gzip")) {
                        return extractFromGzip(part);
                    } else if (lowerFilename.endsWith(".json")) {
                        byte[] content = getPartContent(part);
                        return new String(content);
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to extract content from attachment: {}", e.getMessage());
            }
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
     * Gets filename from Content-Disposition (filename=) or Content-Type (name=).
     */
    private String getFilename(MimePart part) {
        // Check Content-Disposition first
        MimeHeader cdHeader = part.getHeader("Content-Disposition");
        if (cdHeader != null) {
            String value = cdHeader.getValue();
            int idx = value.toLowerCase().indexOf("filename=");
            if (idx >= 0) {
                String filename = value.substring(idx + 9).trim();
                if (filename.startsWith("\"")) {
                    int end = filename.indexOf("\"", 1);
                    if (end > 0) {
                        return filename.substring(1, end);
                    }
                } else {
                    int end = filename.indexOf(";");
                    return end > 0 ? filename.substring(0, end).trim() : filename.trim();
                }
            }
        }

        // Check Content-Type name=
        MimeHeader ctHeader = part.getHeader("Content-Type");
        if (ctHeader != null) {
            String value = ctHeader.getValue();
            int idx = value.toLowerCase().indexOf("name=");
            if (idx >= 0) {
                String name = value.substring(idx + 5).trim();
                if (name.startsWith("\"")) {
                    int end = name.indexOf("\"", 1);
                    if (end > 0) {
                        return name.substring(1, end);
                    }
                } else {
                    int end = name.indexOf(";");
                    return end > 0 ? name.substring(0, end).trim() : name.trim();
                }
            }
        }

        return null;
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
     * Extracts JSON from GZIP archive.
     */
    private String extractFromGzip(MimePart part) throws IOException {
        byte[] content = getPartContent(part);
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(content))) {
            return new String(gis.readAllBytes());
        }
    }

    /**
     * Sends TLSRPT report to the robin-admin API.
     *
     * @param report   Parsed TLSRPT report JSON.
     * @param endpoint API endpoint URL.
     * @param insecure Whether to skip TLS verification.
     */
    private void sendToAdminApi(JsonObject report, String endpoint, boolean insecure) {
        // Convert report to the format expected by robin-admin
        Map<String, Object> payload = new HashMap<>();

        payload.put("reportId", report.has("report-id") ? report.get("report-id").getAsString() : null);
        payload.put("organizationName", report.has("organization-name") ? report.get("organization-name").getAsString() : null);
        payload.put("contactInfo", report.has("contact-info") ? report.get("contact-info").getAsString() : null);

        // Date range
        if (report.has("date-range")) {
            JsonObject dateRange = report.getAsJsonObject("date-range");
            payload.put("dateBegin", dateRange.has("start-datetime") ? dateRange.get("start-datetime").getAsString() : null);
            payload.put("dateEnd", dateRange.has("end-datetime") ? dateRange.get("end-datetime").getAsString() : null);
        }

        // Policies
        List<Map<String, Object>> policies = new ArrayList<>();
        if (report.has("policies")) {
            JsonArray policiesArray = report.getAsJsonArray("policies");
            int totalSuccess = 0;
            int totalFailure = 0;

            for (JsonElement policyElement : policiesArray) {
                JsonObject policyObj = policyElement.getAsJsonObject();
                Map<String, Object> policyMap = new HashMap<>();

                // Policy details
                if (policyObj.has("policy")) {
                    JsonObject policy = policyObj.getAsJsonObject("policy");
                    policyMap.put("policyType", policy.has("policy-type") ? policy.get("policy-type").getAsString() : null);
                    policyMap.put("policyDomain", policy.has("policy-domain") ? policy.get("policy-domain").getAsString() : null);
                    policyMap.put("mxHost", policy.has("mx-host") ? policy.get("mx-host").getAsString() : null);

                    // Policy strings
                    if (policy.has("policy-string")) {
                        JsonArray policyStrings = policy.getAsJsonArray("policy-string");
                        List<String> strings = new ArrayList<>();
                        for (JsonElement str : policyStrings) {
                            strings.add(str.getAsString());
                        }
                        policyMap.put("policyString", strings);
                    }
                }

                // Summary
                if (policyObj.has("summary")) {
                    JsonObject summary = policyObj.getAsJsonObject("summary");
                    int success = summary.has("total-successful-session-count") ? summary.get("total-successful-session-count").getAsInt() : 0;
                    int failure = summary.has("total-failure-session-count") ? summary.get("total-failure-session-count").getAsInt() : 0;
                    policyMap.put("successCount", success);
                    policyMap.put("failureCount", failure);
                    totalSuccess += success;
                    totalFailure += failure;
                }

                // Failure details
                if (policyObj.has("failure-details")) {
                    JsonArray failureDetails = policyObj.getAsJsonArray("failure-details");
                    List<Map<String, Object>> failures = new ArrayList<>();

                    for (JsonElement failureElement : failureDetails) {
                        JsonObject failure = failureElement.getAsJsonObject();
                        Map<String, Object> failureMap = new HashMap<>();
                        failureMap.put("resultType", failure.has("result-type") ? failure.get("result-type").getAsString() : null);
                        failureMap.put("sendingMtaIp", failure.has("sending-mta-ip") ? failure.get("sending-mta-ip").getAsString() : null);
                        failureMap.put("receivingMxHostname", failure.has("receiving-mx-hostname") ? failure.get("receiving-mx-hostname").getAsString() : null);
                        failureMap.put("receivingIp", failure.has("receiving-ip") ? failure.get("receiving-ip").getAsString() : null);
                        failureMap.put("failedSessionCount", failure.has("failed-session-count") ? failure.get("failed-session-count").getAsInt() : 1);
                        failureMap.put("failureReasonCode", failure.has("failure-reason-code") ? failure.get("failure-reason-code").getAsString() : null);
                        failureMap.put("additionalInfo", failure.has("additional-information") ? failure.get("additional-information").getAsString() : null);
                        failures.add(failureMap);
                    }
                    policyMap.put("failures", failures);
                }

                policies.add(policyMap);
            }

            payload.put("totalSuccess", totalSuccess);
            payload.put("totalFailure", totalFailure);
        }

        payload.put("policies", policies);

        String json = gson.toJson(payload);
        log.debug("Sending TLSRPT report to {}: {}", endpoint, json);

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
                log.error("Failed to send TLSRPT report to robin-admin. Status: {} Response: {}",
                        response.code(), responseBody);
            } else {
                log.debug("Successfully sent TLSRPT report to robin-admin");
            }
        } catch (IOException e) {
            log.error("Error sending TLSRPT report to robin-admin: {}", e.getMessage(), e);
        }
    }
}
