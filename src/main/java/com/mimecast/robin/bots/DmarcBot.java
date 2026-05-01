package com.mimecast.robin.bots;

import com.mimecast.robin.config.server.BotConfig;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.mime.parts.FileMimePart;
import com.mimecast.robin.mime.parts.MimePart;
import com.mimecast.robin.smtp.connection.Connection;
import okhttp3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * DMARC aggregate report processing bot.
 * <p>Parses DMARC reports (RFC 7489) from email attachments and sends them
 * to the robin-admin API for storage and visualization.
 * <p>Supported attachment formats:
 * <ul>
 *   <li>application/zip - ZIP archive containing XML report</li>
 *   <li>application/gzip - GZIP compressed XML report</li>
 *   <li>application/xml - Plain XML report</li>
 *   <li>text/xml - Plain XML report</li>
 * </ul>
 */
public class DmarcBot implements BotProcessor {
    private static final Logger log = LogManager.getLogger(DmarcBot.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

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
                    // Enable SNI
                    javax.net.ssl.SSLParameters params = socket.getSSLParameters();
                    params.setServerNames(List.of(new javax.net.ssl.SNIHostName(host)));
                    socket.setSSLParameters(params);
                    return socket;
                }

                @Override
                public java.net.Socket createSocket(String host, int port) throws IOException {
                    javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) baseFactory.createSocket(host, port);
                    javax.net.ssl.SSLParameters params = socket.getSSLParameters();
                    params.setServerNames(List.of(new javax.net.ssl.SNIHostName(host)));
                    socket.setSSLParameters(params);
                    return socket;
                }

                @Override
                public java.net.Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort) throws IOException {
                    javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) baseFactory.createSocket(host, port, localHost, localPort);
                    javax.net.ssl.SSLParameters params = socket.getSSLParameters();
                    params.setServerNames(List.of(new javax.net.ssl.SNIHostName(host)));
                    socket.setSSLParameters(params);
                    return socket;
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
                    .connectionSpecs(List.of(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
                    .build();
        } catch (Exception e) {
            log.warn("Failed to create insecure HTTP client, falling back to standard client: {}", e.getMessage());
            return httpClient;
        }
    }

    @Override
    public void process(Connection connection, EmailParser emailParser, String botAddress, BotConfig.BotDefinition botDefinition) {
        log.info("Processing DMARC bot for address: {} from session UID: {}",
                botAddress, connection.getSession().getUID());

        if (emailParser == null) {
            log.warn("DMARC bot received null email parser, cannot process");
            return;
        }

        // Get endpoint from bot config.
        String endpoint = botDefinition != null ? botDefinition.getEndpoint() : "";
        boolean insecure = botDefinition != null && botDefinition.isInsecure();

        if (endpoint.isEmpty()) {
            log.warn("DMARC bot has no endpoint configured, cannot send report");
            return;
        }

        try {
            // Parse the full email to extract attachments.
            emailParser.parse();

            // Find DMARC report attachment.
            byte[] xmlContent = extractDmarcReport(emailParser);
            if (xmlContent == null) {
                log.warn("No DMARC report attachment found in email");
                return;
            }

            // Parse DMARC XML report.
            Map<String, Object> report = parseDmarcXml(xmlContent);
            if (report == null) {
                log.error("Failed to parse DMARC XML report");
                return;
            }

            // Send to robin-admin API.
            sendToAdminApi(report, endpoint, insecure);

            log.info("Successfully processed DMARC report: {}", report.get("reportId"));

        } catch (Exception e) {
            log.error("Error processing DMARC bot for session UID: {}",
                    connection.getSession().getUID(), e);
        }
    }

    /**
     * Extracts DMARC report XML from email attachments.
     *
     * @param emailParser Parsed email.
     * @return XML content as bytes, or null if not found.
     */
    private byte[] extractDmarcReport(EmailParser emailParser) {
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
                if (contentType.contains("application/zip") || contentType.contains("application/x-zip")) {
                    return extractFromZip(part);
                } else if (contentType.contains("application/gzip") || contentType.contains("application/x-gzip")) {
                    return extractFromGzip(part);
                } else if (contentType.contains("application/xml") || contentType.contains("text/xml")) {
                    return getPartContent(part);
                }
                
                // For octet-stream, check filename extension
                if (contentType.contains("application/octet-stream") && filename != null) {
                    String lowerFilename = filename.toLowerCase();
                    if (lowerFilename.endsWith(".gz") || lowerFilename.endsWith(".gzip")) {
                        return extractFromGzip(part);
                    } else if (lowerFilename.endsWith(".zip")) {
                        return extractFromZip(part);
                    } else if (lowerFilename.endsWith(".xml")) {
                        return getPartContent(part);
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
        // Try Content-Disposition filename= first
        MimeHeader cdHeader = part.getHeader("Content-Disposition");
        if (cdHeader != null) {
            String filename = extractParameter(cdHeader.getValue(), "filename=");
            if (filename != null) return filename;
        }
        
        // Fall back to Content-Type name= parameter
        MimeHeader ctHeader = part.getHeader("Content-Type");
        if (ctHeader != null) {
            String name = extractParameter(ctHeader.getValue(), "name=");
            if (name != null) return name;
        }
        
        return null;
    }
    
    /**
     * Extracts a parameter value from a header (e.g., filename= or name=).
     */
    private String extractParameter(String headerValue, String paramName) {
        int idx = headerValue.toLowerCase().indexOf(paramName.toLowerCase());
        if (idx >= 0) {
            String value = headerValue.substring(idx + paramName.length()).trim();
            // Remove quotes if present
            if (value.startsWith("\"")) {
                int endQuote = value.indexOf("\"", 1);
                if (endQuote > 0) {
                    return value.substring(1, endQuote);
                }
            }
            // No quotes - take until semicolon or end
            int semi = value.indexOf(";");
            return semi > 0 ? value.substring(0, semi).trim() : value.trim();
        }
        return null;
    }

    /**
     * Gets content bytes from a MIME part.
     */
    private byte[] getPartContent(MimePart part) throws IOException {
        if (part instanceof FileMimePart filePart && filePart.getFile() != null) {
            return Files.readAllBytes(filePart.getFile().toPath());
        }
        return part.getBytes();
    }

    /**
     * Extracts XML from ZIP archive.
     */
    private byte[] extractFromZip(MimePart part) throws IOException {
        byte[] content = getPartContent(part);
        log.debug("ZIP content length: {} bytes, first bytes: {}", 
                content.length, 
                content.length > 10 ? new String(content, 0, Math.min(50, content.length)) : "empty");
        
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                log.debug("ZIP entry: {}", entry.getName());
                if (entry.getName().endsWith(".xml")) {
                    return zis.readAllBytes();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read ZIP: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extracts XML from GZIP archive.
     */
    private byte[] extractFromGzip(MimePart part) throws IOException {
        byte[] content = getPartContent(part);
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(content))) {
            return gis.readAllBytes();
        }
    }

    /**
     * Parses DMARC XML report into a structured map.
     *
     * @param xmlContent XML content bytes.
     * @return Parsed report data, or null on error.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseDmarcXml(byte[] xmlContent) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xmlContent));
            doc.getDocumentElement().normalize();

            Map<String, Object> report = new HashMap<>();

            // Parse report_metadata.
            Element metadata = (Element) doc.getElementsByTagName("report_metadata").item(0);
            if (metadata != null) {
                report.put("reportId", getElementText(metadata, "report_id"));
                report.put("orgName", getElementText(metadata, "org_name"));
                report.put("email", getElementText(metadata, "email"));

                Element dateRange = (Element) metadata.getElementsByTagName("date_range").item(0);
                if (dateRange != null) {
                    report.put("dateBegin", Long.parseLong(getElementText(dateRange, "begin")));
                    report.put("dateEnd", Long.parseLong(getElementText(dateRange, "end")));
                }
            }

            // Parse policy_published.
            Element policy = (Element) doc.getElementsByTagName("policy_published").item(0);
            if (policy != null) {
                report.put("domain", getElementText(policy, "domain"));
                report.put("policy", getElementText(policy, "p"));
                report.put("subdomainPolicy", getElementText(policy, "sp"));
                report.put("dkimAlignment", getElementText(policy, "adkim"));
                report.put("spfAlignment", getElementText(policy, "aspf"));
                report.put("pct", getElementText(policy, "pct"));
            }

            // Parse records.
            List<Map<String, Object>> records = new ArrayList<>();
            NodeList recordNodes = doc.getElementsByTagName("record");
            int totalCount = 0;
            int errorCount = 0;

            for (int i = 0; i < recordNodes.getLength(); i++) {
                Element recordEl = (Element) recordNodes.item(i);
                Map<String, Object> record = new HashMap<>();

                // Row data.
                Element row = (Element) recordEl.getElementsByTagName("row").item(0);
                if (row != null) {
                    record.put("sourceIp", getElementText(row, "source_ip"));
                    int count = Integer.parseInt(getElementTextOrDefault(row, "count", "1"));
                    record.put("count", count);
                    totalCount += count;

                    Element policyEvaluated = (Element) row.getElementsByTagName("policy_evaluated").item(0);
                    if (policyEvaluated != null) {
                        String disposition = getElementText(policyEvaluated, "disposition");
                        record.put("disposition", disposition);
                        record.put("dkim", getElementText(policyEvaluated, "dkim"));
                        record.put("spf", getElementText(policyEvaluated, "spf"));

                        if (!"none".equalsIgnoreCase(disposition)) {
                            errorCount += count;
                        }

                        // Check for policy override reasons.
                        Element reason = (Element) policyEvaluated.getElementsByTagName("reason").item(0);
                        if (reason != null) {
                            record.put("reasonType", getElementText(reason, "type"));
                            record.put("reasonComment", getElementText(reason, "comment"));
                        }
                    }
                }

                // Identifiers.
                Element identifiers = (Element) recordEl.getElementsByTagName("identifiers").item(0);
                if (identifiers != null) {
                    record.put("headerFrom", getElementText(identifiers, "header_from"));
                    record.put("envelopeFrom", getElementText(identifiers, "envelope_from"));
                    record.put("envelopeTo", getElementText(identifiers, "envelope_to"));
                }

                // Auth results.
                Element authResults = (Element) recordEl.getElementsByTagName("auth_results").item(0);
                if (authResults != null) {
                    // DKIM auth.
                    Element dkim = (Element) authResults.getElementsByTagName("dkim").item(0);
                    if (dkim != null) {
                        record.put("dkimDomain", getElementText(dkim, "domain"));
                        record.put("dkimResult", getElementText(dkim, "result"));
                        record.put("dkimSelector", getElementText(dkim, "selector"));
                    }

                    // SPF auth.
                    Element spf = (Element) authResults.getElementsByTagName("spf").item(0);
                    if (spf != null) {
                        record.put("spfDomain", getElementText(spf, "domain"));
                        record.put("spfResult", getElementText(spf, "result"));
                        record.put("spfScope", getElementText(spf, "scope"));
                    }
                }

                records.add(record);
            }

            report.put("records", records);
            report.put("totalCount", totalCount);
            report.put("errorCount", errorCount);

            return report;

        } catch (Exception e) {
            log.error("Failed to parse DMARC XML: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Gets text content of a child element.
     */
    private String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0 && nodes.item(0).getTextContent() != null) {
            return nodes.item(0).getTextContent().trim();
        }
        return "";
    }

    /**
     * Gets text content of a child element with default value.
     */
    private String getElementTextOrDefault(Element parent, String tagName, String defaultValue) {
        String value = getElementText(parent, tagName);
        return value.isEmpty() ? defaultValue : value;
    }

    /**
     * Sends parsed DMARC report to robin-admin API.
     *
     * @param report   Parsed report data.
     * @param endpoint Full endpoint URL.
     * @param insecure Whether to skip TLS certificate verification.
     */
    private void sendToAdminApi(Map<String, Object> report, String endpoint, boolean insecure) {
        try {
            String json = toJson(report);
            RequestBody body = RequestBody.create(json, JSON);
            Request request = new Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .build();

            OkHttpClient client = insecure ? insecureHttpClient : httpClient;
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("Failed to send DMARC report to admin API: {} {}",
                            response.code(), response.message());
                } else {
                    log.info("Successfully sent DMARC report to admin API at {}", endpoint);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to connect to admin API at {}: {}", endpoint, e.getMessage());
        }
    }

    /**
     * Converts a map to JSON string.
     */
    @SuppressWarnings("unchecked")
    private String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) return "\"" + escapeJson((String) obj) + "\"";
        if (obj instanceof Number || obj instanceof Boolean) return obj.toString();
        if (obj instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJson(list.get(i)));
            }
            return sb.append("]").toString();
        }
        if (obj instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escapeJson(entry.getKey().toString())).append("\":");
                sb.append(toJson(entry.getValue()));
            }
            return sb.append("}").toString();
        }
        return "\"" + escapeJson(obj.toString()) + "\"";
    }

    /**
     * Escapes special JSON characters.
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public String getName() {
        return "dmarc";
    }
}
