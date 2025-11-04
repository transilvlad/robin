package com.mimecast.robin.mime.headers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.mail.internet.MimeUtility;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MIME email header content injector.
 * <p>
 * Processes email bytes to inject tags into header values and append new headers.
 * Tags can be prepended to any header value (e.g., [SPAM] for Subject header).
 * New headers can be added after the existing headers, right before the content.
 * <p>
 * If a header value is encoded (RFC 2047), the tag is encoded the same way and
 * the value is properly folded onto the next line with appropriate whitespace.
 */
public class HeaderWrangler {
    private static final Logger log = LogManager.getLogger(HeaderWrangler.class);

    /**
     * Pattern to match RFC 2047 encoded words.
     */
    private static final Pattern ENCODED_WORD_PATTERN = Pattern.compile(
            "=\\?([^?]+)\\?([BQbq])\\?([^?]+)\\?="
    );

    /**
     * List of header tags to inject.
     */
    private final List<HeaderTag> headerTags = new ArrayList<>();

    /**
     * List of headers to append.
     */
    private final List<MimeHeader> headersToAppend = new ArrayList<>();

    /**
     * Adds a header tag to be injected.
     *
     * @param headerTag Header tag instance.
     * @return Self for chaining.
     */
    public HeaderWrangler addHeaderTag(HeaderTag headerTag) {
        this.headerTags.add(headerTag);
        return this;
    }

    /**
     * Adds a header to be appended after existing headers.
     *
     * @param header MimeHeader instance.
     * @return Self for chaining.
     */
    public HeaderWrangler addHeader(MimeHeader header) {
        this.headersToAppend.add(header);
        return this;
    }

    /**
     * Processes the email bytes and applies header tags and new headers.
     *
     * @param emailBytes Original email as bytes.
     * @return Modified email as bytes.
     * @throws IOException If processing fails.
     */
    public byte[] process(byte[] emailBytes) throws IOException {
        String email = new String(emailBytes, StandardCharsets.UTF_8);
        String[] lines = email.split("\r?\n", -1);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int lineIndex = 0;
        boolean inHeaders = true;

        while (lineIndex < lines.length && inHeaders) {
            String line = lines[lineIndex];

            // Check if we've reached the end of headers.
            if (line.isEmpty() || line.startsWith("--")) {
                inHeaders = false;

                // Append new headers before the blank line or boundary.
                for (MimeHeader header : headersToAppend) {
                    output.write(header.toString().getBytes(StandardCharsets.UTF_8));
                }

                // Write the current line (blank line or boundary).
                output.write(line.getBytes(StandardCharsets.UTF_8));
                output.write("\r\n".getBytes(StandardCharsets.UTF_8));
                lineIndex++;
                continue;
            }

            // Check if this line is a continuation of the previous header (starts with whitespace).
            if (line.startsWith(" ") || line.startsWith("\t")) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
                output.write("\r\n".getBytes(StandardCharsets.UTF_8));
                lineIndex++;
                continue;
            }

            // Parse header name and value.
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String headerName = line.substring(0, colonIndex).trim();
                String headerValue = line.substring(colonIndex + 1).trim();

                // Collect continuation lines.
                StringBuilder fullValue = new StringBuilder(headerValue);
                int nextIndex = lineIndex + 1;
                while (nextIndex < lines.length) {
                    String nextLine = lines[nextIndex];
                    if (nextLine.startsWith(" ") || nextLine.startsWith("\t")) {
                        fullValue.append(nextLine);
                        nextIndex++;
                    } else {
                        break;
                    }
                }

                // Check if we need to tag this header.
                String taggedValue = getTaggedValue(headerName, fullValue.toString());

                if (!taggedValue.equals(fullValue.toString())) {
                    // Write tagged header.
                    output.write((headerName + ": " + taggedValue).getBytes(StandardCharsets.UTF_8));
                    output.write("\r\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    // Write original header lines.
                    output.write(line.getBytes(StandardCharsets.UTF_8));
                    output.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    for (int i = lineIndex + 1; i < nextIndex; i++) {
                        output.write(lines[i].getBytes(StandardCharsets.UTF_8));
                        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    }
                }

                lineIndex = nextIndex;
            } else {
                // Not a valid header line, write as-is.
                output.write(line.getBytes(StandardCharsets.UTF_8));
                output.write("\r\n".getBytes(StandardCharsets.UTF_8));
                lineIndex++;
            }
        }

        // Write remaining lines (body content).
        while (lineIndex < lines.length) {
            output.write(lines[lineIndex].getBytes(StandardCharsets.UTF_8));
            if (lineIndex < lines.length - 1 || email.endsWith("\n")) {
                output.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            lineIndex++;
        }

        return output.toByteArray();
    }

    /**
     * Gets the tagged value for a header if a tag is configured.
     *
     * @param headerName  Header name.
     * @param headerValue Original header value.
     * @return Tagged header value or original if no tag configured.
     */
    private String getTaggedValue(String headerName, String headerValue) {
        for (HeaderTag headerTag : headerTags) {
            if (headerTag.getHeaderName().equalsIgnoreCase(headerName)) {
                return applyTag(headerValue, headerTag.getTag());
            }
        }
        return headerValue;
    }

    /**
     * Applies a tag to a header value, handling encoding if necessary.
     *
     * @param headerValue Original header value.
     * @param tag         Tag to prepend.
     * @return Tagged header value.
     */
    private String applyTag(String headerValue, String tag) {
        // Check if the header value is encoded (RFC 2047).
        Matcher matcher = ENCODED_WORD_PATTERN.matcher(headerValue.trim());

        if (matcher.find()) {
            // Extract encoding information.
            String charset = matcher.group(1);
            String encoding = matcher.group(2);

            try {
                // Encode the tag using the same encoding.
                String encodedTag = MimeUtility.encodeText(tag + " ", charset, encoding);

                // Insert the encoded tag before the encoded value.
                // Handle multi-line by ensuring proper folding.
                String result = encodedTag + headerValue.trim();

                // If the result is too long, fold it properly.
                return foldHeaderValue(result);
            } catch (UnsupportedEncodingException e) {
                log.warn("Failed to encode tag with charset {}: {}", charset, e.getMessage());
                // Fall back to simple prepending.
                return tag + " " + headerValue;
            }
        } else {
            // Not encoded, simply prepend the tag.
            return tag + " " + headerValue;
        }
    }

    /**
     * Folds a header value to fit within recommended line length.
     * RFC 5322 recommends lines should be no more than 78 characters.
     *
     * @param headerValue Header value to fold.
     * @return Folded header value.
     */
    private String foldHeaderValue(String headerValue) {
        // If the value is already short enough, return as-is.
        if (headerValue.length() <= 78) {
            return headerValue;
        }

        // For encoded words, split at encoded word boundaries.
        StringBuilder folded = new StringBuilder();
        int lineLength = 0;
        Matcher matcher = ENCODED_WORD_PATTERN.matcher(headerValue);
        int lastEnd = 0;

        while (matcher.find()) {
            String beforeMatch = headerValue.substring(lastEnd, matcher.start());
            String encodedWord = matcher.group(0);

            // Add text before encoded word.
            if (!beforeMatch.isEmpty()) {
                if (lineLength + beforeMatch.length() > 78) {
                    folded.append("\r\n\t");
                    lineLength = 0;
                }
                folded.append(beforeMatch);
                lineLength += beforeMatch.length();
            }

            // Add encoded word.
            if (lineLength + encodedWord.length() > 78 && lineLength > 0) {
                folded.append("\r\n\t");
                lineLength = 0;
            }
            folded.append(encodedWord);
            lineLength += encodedWord.length();

            lastEnd = matcher.end();
        }

        // Add remaining text.
        if (lastEnd < headerValue.length()) {
            String remaining = headerValue.substring(lastEnd);
            if (lineLength + remaining.length() > 78 && lineLength > 0) {
                folded.append("\r\n\t");
            }
            folded.append(remaining);
        }

        return folded.toString();
    }
}
