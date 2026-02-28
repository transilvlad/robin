package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mimecast.robin.config.client.CaseConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.util.GsonExclusionStrategy;
import com.mimecast.robin.util.PathUtils;
import com.sun.net.httpserver.HttpExchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Utility methods shared across API endpoint handlers.
 *
 * <p>Provides common functionality for request parsing, response formatting,
 * file handling, and JSON serialization used by multiple handlers.
 */
public final class ApiEndpointUtils {
    private static final Logger log = LogManager.getLogger(ApiEndpointUtils.class);

    /**
     * Shared Gson instance with exclusion strategy for session serialization.
     */
    private static final Gson GSON = new GsonBuilder()
            .addSerializationExclusionStrategy(new GsonExclusionStrategy())
            .setPrettyPrinting()
            .create();

    /**
     * Simple Gson instance without exclusion strategy for general parsing.
     */
    private static final Gson GSON_SIMPLE = new Gson();

    private ApiEndpointUtils() {
        // Utility class - prevent instantiation.
    }

    /**
     * Returns the shared Gson instance with exclusion strategy.
     *
     * @return Gson instance configured for session serialization.
     */
    public static Gson getGson() {
        return GSON;
    }

    /**
     * Returns the simple Gson instance for general parsing.
     *
     * @return Gson instance without exclusion strategy.
     */
    public static Gson getGsonSimple() {
        return GSON_SIMPLE;
    }

    /**
     * Reads the full request body into a string using UTF-8 encoding.
     *
     * @param is Input stream of the request body.
     * @return String content of the request body.
     * @throws IOException If an I/O error occurs while reading.
     */
    public static String readBody(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            String s = sb.toString();
            log.debug("Read request body ({} bytes)", s.getBytes(StandardCharsets.UTF_8).length);
            return s;
        }
    }

    /**
     * Parses the query string from a URI into a map of key-value pairs.
     *
     * @param uri Request URI.
     * @return Map of query parameter names to values.
     */
    public static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            log.debug("No query string in URI: {}", uri);
            return map;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = urlDecode(pair.substring(0, idx));
                String val = urlDecode(pair.substring(idx + 1));
                map.put(key, val);
            } else {
                map.put(urlDecode(pair), "");
            }
        }
        log.debug("Parsed query params: {}", map);
        return map;
    }

    /**
     * URL-decodes a string using UTF-8 encoding.
     *
     * @param s Encoded string.
     * @return Decoded string.
     */
    public static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /**
     * Builds a CaseConfig from a parsed JSON map.
     *
     * @param input Map containing case configuration properties.
     * @return Configured CaseConfig instance.
     */
    public static CaseConfig buildCaseConfig(Map<?, ?> input) {
        CaseConfig caseConfig = new CaseConfig();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = caseConfig.getMap();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() != null) {
                map.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return caseConfig;
    }

    /**
     * Checks if the request is a raw file upload based on Content-Type header.
     *
     * @param exchange HTTP exchange to check.
     * @return {@code true} if the request is multipart/form-data, message/rfc822, or application/octet-stream.
     */
    public static boolean isRawUploadRequest(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null) {
            return false;
        }
        String ct = contentType.toLowerCase();
        return ct.contains("multipart/form-data")
                || ct.contains("message/rfc822")
                || ct.contains("application/octet-stream");
    }

    /**
     * Parses a comma-separated list of recipients.
     *
     * @param input Comma-separated recipient addresses.
     * @return List of trimmed, non-blank recipient addresses.
     */
    public static List<String> parseRecipients(String input) {
        List<String> out = new ArrayList<>();
        if (input == null || input.isBlank()) {
            return out;
        }
        for (String part : input.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /**
     * Reads uploaded EML bytes from the request, handling multipart form-data if needed.
     *
     * @param exchange HTTP exchange containing the upload.
     * @return Byte array of the uploaded EML content.
     * @throws IOException If an I/O error occurs while reading.
     */
    public static byte[] readUploadedEmlBytes(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (body.length == 0) {
            return body;
        }

        if (contentType == null || !contentType.toLowerCase().contains("multipart/form-data")) {
            return body;
        }

        String boundary = extractMultipartBoundary(contentType);
        if (boundary == null || boundary.isBlank()) {
            return body;
        }

        byte[] extracted = extractMultipartFileBytes(body, boundary);
        return extracted != null ? extracted : body;
    }

    /**
     * Extracts the boundary string from a multipart/form-data Content-Type header.
     *
     * @param contentType Content-Type header value.
     * @return Boundary string, or {@code null} if not found.
     */
    public static String extractMultipartBoundary(String contentType) {
        for (String token : contentType.split(";")) {
            String t = token.trim();
            if (t.toLowerCase().startsWith("boundary=")) {
                String boundary = t.substring("boundary=".length()).trim();
                if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() >= 2) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
        }
        return null;
    }

    /**
     * Extracts file content from a multipart/form-data body.
     *
     * @param multipartBody Raw multipart body bytes.
     * @param boundary      Multipart boundary string.
     * @return File content bytes, or {@code null} if no file part found.
     */
    public static byte[] extractMultipartFileBytes(byte[] multipartBody, String boundary) {
        Charset c = StandardCharsets.ISO_8859_1;
        String raw = new String(multipartBody, c);
        String delimiter = "--" + boundary;
        String[] parts = raw.split(Pattern.quote(delimiter));
        for (String part : parts) {
            if (part == null || part.isBlank() || "--".equals(part.trim())) {
                continue;
            }
            int split = part.indexOf("\r\n\r\n");
            if (split < 0) {
                continue;
            }

            String headers = part.substring(0, split).toLowerCase();
            if (!headers.contains("filename=") && !headers.contains("name=\"file\"")) {
                continue;
            }

            String content = part.substring(split + 4);
            if (content.endsWith("\r\n")) {
                content = content.substring(0, content.length() - 2);
            }
            if (content.endsWith("--")) {
                content = content.substring(0, content.length() - 2);
            }
            return content.getBytes(c);
        }
        return null;
    }

    /**
     * Persists an uploaded EML file to storage.
     *
     * @param sender            Sender email address.
     * @param emlBytes          EML content bytes.
     * @param requestedFileName Optional requested filename.
     * @return Path to the persisted file.
     * @throws IOException If an I/O error occurs while writing.
     */
    public static String persistUploadedEml(String sender, byte[] emlBytes, String requestedFileName) throws IOException {
        String basePath = Config.getServer().getStorage().getStringProperty("path", "/tmp/store");
        boolean storageEnabled = Config.getServer().getStorage().getBooleanProperty("enabled", true);
        boolean localMailbox = Config.getServer().getStorage().getBooleanProperty("localMailbox", false);
        String outboundFolder = Config.getServer().getStorage().getStringProperty("outboundFolder", ".Sent/new");

        String fileName = normalizeUploadFileName(requestedFileName);

        Path targetDir;
        if (storageEnabled && localMailbox && sender.contains("@")) {
            String[] splits = sender.split("@", 2);
            String username = PathUtils.normalize(splits[0]);
            String domain = PathUtils.normalize(splits[1]);
            targetDir = Paths.get(basePath, domain, username, outboundFolder);
        } else {
            targetDir = Paths.get(basePath, "queue");
        }

        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(fileName);
        if (Files.exists(target)) {
            target = targetDir.resolve("eml-" + UUID.randomUUID() + ".eml");
        }

        Files.write(target, emlBytes);
        return target.toString();
    }

    /**
     * Normalizes an upload filename to ensure it is safe and has .eml extension.
     *
     * @param requested Requested filename (may be null or contain path).
     * @return Normalized safe filename with .eml extension.
     */
    public static String normalizeUploadFileName(String requested) {
        String name = requested != null ? requested.trim() : "";
        if (name.isBlank()) {
            name = "eml-" + System.currentTimeMillis() + ".eml";
        }
        name = name.replace("\\", "/");
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        if (!name.toLowerCase().endsWith(".eml")) {
            name = name + ".eml";
        }
        if (name.contains("..")) {
            name = "eml-" + UUID.randomUUID() + ".eml";
        }
        return name;
    }

    /**
     * Escapes a string for safe inclusion in JSON.
     *
     * @param s String to escape.
     * @return Escaped string with backslashes and quotes escaped.
     */
    public static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Escapes minimal HTML characters for safe inclusion in HTML content.
     *
     * @param s String to escape.
     * @return Escaped string with HTML special characters replaced.
     */
    public static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                case '&' -> out.append("&amp;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Parses the JSON body of a request into a map.
     *
     * @param is Input stream of the request body.
     * @return Parsed map of JSON properties, or empty map if body is blank.
     * @throws IOException If the JSON body is invalid.
     */
    public static Map<String, Object> parseJsonBody(InputStream is) throws IOException {
        String body = readBody(is).trim();
        if (body.isBlank()) return new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = GSON_SIMPLE.fromJson(body, Map.class);
            return map != null ? map : new HashMap<>();
        } catch (Exception e) {
            throw new IOException("Invalid JSON body");
        }
    }

    /**
     * Converts an object to a list of strings.
     *
     * @param value Object to convert (expected to be a List).
     * @return List of strings, or empty list if value is not a List.
     */
    public static List<String> toStringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) out.add(String.valueOf(item));
            }
        }
        return out;
    }

    /**
     * Converts an object to an integer value.
     *
     * @param value    Object to convert (Number, String, or other).
     * @param fallback Default value if conversion fails.
     * @return Integer value, or fallback if value is null or cannot be parsed.
     */
    public static int toInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * Checks if the request accepts JSON responses based on the Accept header.
     *
     * @param exchange HTTP exchange to check.
     * @return {@code true} if the Accept header contains "application/json".
     */
    public static boolean acceptsJson(HttpExchange exchange) {
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        return accept != null && accept.toLowerCase().contains("application/json");
    }
}

