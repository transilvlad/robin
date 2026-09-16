package com.mimecast.robin.mime.headers;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.mail.internet.HeaderTokenizer;
import javax.mail.internet.MimeUtility;
import javax.mail.internet.ParseException;
import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MIME header container.
 */
public class MimeHeader {
    private static final Logger log = LogManager.getLogger(MimeHeader.class);

    /**
     * Pattern for a valid header field name.
     * <p>Deliberately conservative (letters, digits, and hyphen only) rather than the full
     * RFC 5322 {@code ftext} range, so malformed or attacker-crafted lines (e.g. plain body
     * text that happens to contain a colon) are rejected rather than captured as a header
     * under an unexpected name.
     */
    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("[A-Za-z0-9-]+");

    /**
     * Pattern for the RFC 2231 extended-value shape: {@code charset'language'value}.
     * The language tag may be empty (e.g. {@code UTF-8''value}).
     */
    private static final Pattern EXTENDED_VALUE_PATTERN = Pattern.compile("^([^']*)'[^']*'(.*)$");

    /**
     * Header name.
     */
    protected final String name;

    /**
     * Header value.
     */
    protected final String value;

    /**
     * Header clean value.
     */
    protected String cleanValue;

    /**
     * Header value with RFC 2047 encoded-words decoded.
     */
    protected String decodedValue;

    /**
     * Header parameters.
     */
    protected final Map<String, String> parameters = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    /**
     * Constructs a new MimeHeader instance with given header string.
     *
     * @param header Complete header.
     */
    public MimeHeader(String header) {
        String[] splits = header.trim().split(":", 2);
        this.name = splits.length > 0 ? splits[0].trim() : "x-unknown";
        this.value = splits.length > 1 ? splits[1].trim() : "";
    }

    /**
     * Constructs a new MimeHeader instance with given name and value.
     *
     * @param name  Header name.
     * @param value Header value.
     */
    public MimeHeader(String name, String value) {
        this.name = name;
        this.value = value;
    }

    /**
     * Gets header name.
     *
     * @return Header name.
     */
    public String getName() {
        return name;
    }

    /**
     * Checks if the given header name contains only characters valid for a header field name.
     * See {@link #VALID_NAME_PATTERN}.
     *
     * @param name Header name to validate.
     * @return true if the name is non-blank and contains only letters, digits, or hyphens.
     */
    public static boolean isValidName(String name) {
        return name != null && VALID_NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Checks if this header's name is valid. See {@link #isValidName(String)}.
     *
     * @return true if this header's name is valid.
     */
    public boolean isValid() {
        return isValidName(name);
    }

    /**
     * Gets header value.
     *
     * @return Header value.
     */
    public String getValue() {
        return value;
    }

    /**
     * Gets header value with RFC 2047 encoded-words (e.g. {@code =?UTF-8?B?...?=}) decoded to
     * readable text.
     * <p>{@link #getValue()} deliberately returns the raw wire value untouched (needed for
     * signature verification and faithful pass-through); use this method when a human-readable
     * value is wanted instead, such as for display or reporting.
     * <p>Falls back to the raw value if the header contains no encoded words, or decoding fails
     * (e.g. an unsupported or malformed charset).
     *
     * @return Decoded header value.
     */
    public String getDecodedValue() {
        if (decodedValue == null) {
            if (value.contains("=?")) {
                try {
                    decodedValue = MimeUtility.decodeText(value);
                } catch (UnsupportedEncodingException e) {
                    log.debug("Failed to decode header value, keeping raw: {}", e.getMessage());
                    decodedValue = value;
                }
            } else {
                decodedValue = value;
            }
        }
        return decodedValue;
    }

    /**
     * Gets header clean value.
     *
     * @return Header value.
     */
    public String getCleanValue() {
        parseValue();
        return cleanValue;
    }

    /**
     * Gets header parameter with given name.
     *
     * @param name Parameter name.
     * @return Header value.
     */
    public String getParameter(String name) {
        parseValue();
        return parameters.get(name);
    }

    /**
     * Gets an RFC 2231/5987 extended parameter (e.g. {@code filename*=UTF-8''%C3%A9vil.exe}),
     * decoded to its actual value.
     * <p>Handled separately from {@link #getParameter(String)}: the star form's
     * {@code charset'language'value} shape doesn't tokenize correctly under the generic
     * parameter parser, since the single quotes are themselves token delimiters.
     * <p>Only the single-segment form is supported, not the {@code name*0*}/{@code name*1*}
     * continuation form for values split across multiple parameters.
     *
     * @param name Base parameter name, without the trailing {@code *} (e.g. "filename").
     * @return Decoded parameter value, or null if the extended form isn't present.
     */
    public String getExtendedParameter(String name) {
        Matcher paramMatcher = Pattern.compile("(?i)(?:^|;)\\s*" + Pattern.quote(name) + "\\*\\s*=\\s*([^;]+)")
                .matcher(value);
        if (!paramMatcher.find()) {
            return null;
        }

        String raw = paramMatcher.group(1).trim();
        if (raw.length() >= 2 && ((raw.startsWith("\"") && raw.endsWith("\"")) || (raw.startsWith("'") && raw.endsWith("'")))) {
            raw = raw.substring(1, raw.length() - 1);
        }

        // Format: charset'language'percent-encoded-value.
        Matcher extValueMatcher = EXTENDED_VALUE_PATTERN.matcher(raw);
        if (!extValueMatcher.matches()) {
            return percentDecode(raw, "UTF-8");
        }

        String charset = extValueMatcher.group(1);
        String javaCharset;
        try {
            javaCharset = MimeUtility.javaCharset(charset);
        } catch (Exception e) {
            javaCharset = "UTF-8";
        }
        return percentDecode(extValueMatcher.group(2), javaCharset);
    }

    /**
     * Decodes a percent-encoded ({@code %XX}) string. Unlike {@link java.net.URLDecoder}, a
     * literal {@code +} is left untouched rather than turned into a space, matching RFC 2231.
     *
     * @param value   Percent-encoded value.
     * @param charset Charset to decode the resulting bytes with.
     * @return Decoded string, or the original value if the charset is unsupported.
     */
    private static String percentDecode(String value, String charset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 3 <= value.length()) {
                try {
                    out.write(Integer.parseInt(value.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                    // Not a valid escape; fall through and write the literal '%'.
                }
            }
            out.write(c);
        }

        try {
            return out.toString(charset);
        } catch (UnsupportedEncodingException e) {
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * Gets header parameter with given name.
     */
    public void parseValue() {
        if (cleanValue == null) {
            List<String> tokens = new ArrayList<>();

            try {
                HeaderTokenizer tokenizer = new HeaderTokenizer(value, " ,;:\"'\t=\\", true);
                HeaderTokenizer.Token htt = tokenizer.next();
                while (HeaderTokenizer.Token.EOF != htt.getType()) {
                    String tokenValue = htt.getValue().trim();

                    // Save clean value.
                    if (cleanValue == null) {
                        cleanValue = tokenValue;
                        htt = tokenizer.next();
                        continue;
                    }

                    // Save parameter.
                    if (StringUtils.isNotBlank(tokenValue) &&
                            !tokenValue.equals(",") &&
                            !tokenValue.equals(";") &&
                            !tokenValue.equals("'")) {

                        tokens.add(tokenValue);
                    }
                    htt = tokenizer.next();
                }
            } catch (ParseException e) {
                log.error("Parse exception: {}", e.getMessage());
            }

            getParameters(tokens);
        }
    }

    /**
     * Gets parameters from tokens.
     *
     * @param tokens List of string tokens.
     */
    private void getParameters(List<String> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (i > 0 && token.equals("=")) {
                String headerName = tokens.get(i - 1);

                if (tokens.size() > i + 1) {
                    String headerValue = tokens.get(i + 1);
                    parameters.put(headerName, headerValue);
                }
            }
        }
    }

    /**
     * Returns a string representation of the header by
     * combinging name and value separated by collon and space.
     *
     * @return Header string.
     */
    @Override
    public String toString() {
        return name + ": " + value + "\r\n";
    }
}
