package com.mimecast.robin.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decoder for the classic Unix uuencode format.
 * <p>Handles legacy attachments pasted as plain text, starting with a {@code begin MODE FILENAME}
 * line and ending with an {@code end} line - predating MIME multipart attachments, but still
 * occasionally seen, and sometimes used deliberately to slip content past scanners that no
 * longer recognise the format.
 */
public final class UuencodeDecoder {

    private static final Pattern BEGIN_LINE = Pattern.compile("(?m)^begin\\s+[0-7]{3,4}\\s+(\\S+)");

    private UuencodeDecoder() {
        // Utility class, no instantiation.
    }

    /**
     * Result of a successful uuencode decode.
     *
     * @param content  Decoded binary content.
     * @param filename Filename declared on the "begin" line, or null if it was blank.
     */
    public record Result(byte[] content, String filename) {
    }

    /**
     * Checks whether the given content starts with a uuencode "begin" preamble.
     *
     * @param content Raw content bytes.
     * @return true if the content looks like uuencoded data.
     */
    public static boolean looksLikeUuencode(byte[] content) {
        if (content == null) {
            return false;
        }
        return BEGIN_LINE.matcher(new String(content, StandardCharsets.ISO_8859_1)).find();
    }

    /**
     * Decodes uuencoded content.
     *
     * @param content Raw content bytes, expected to contain a uuencode "begin" preamble.
     * @return Decoded result, or empty if the content isn't valid uuencode.
     */
    public static Optional<Result> decode(byte[] content) {
        if (content == null) {
            return Optional.empty();
        }

        String text = new String(content, StandardCharsets.ISO_8859_1);
        Matcher beginMatcher = BEGIN_LINE.matcher(text);
        if (!beginMatcher.find()) {
            return Optional.empty();
        }

        String filename = beginMatcher.group(1).trim();
        int bodyStart = text.indexOf('\n', beginMatcher.end());
        if (bodyStart < 0) {
            return Optional.empty();
        }

        ByteArrayOutputStream decoded = new ByteArrayOutputStream(content.length);
        for (String line : text.substring(bodyStart + 1).split("\r\n|\r|\n")) {
            String trimmed = stripTrailingWhitespace(line);
            if (trimmed.isEmpty() || trimmed.equals("`") || trimmed.equalsIgnoreCase("end")) {
                continue;
            }
            decodeLine(trimmed, decoded);
        }

        if (decoded.size() == 0) {
            return Optional.empty();
        }
        return Optional.of(new Result(decoded.toByteArray(), filename.isEmpty() ? null : filename));
    }

    /**
     * Decodes a single uuencoded line (a length character followed by groups of 4 encoded
     * characters, each group representing up to 3 decoded bytes) and appends its declared
     * number of bytes to the output.
     *
     * @param line Trimmed uuencode data line.
     * @param out  Output buffer to append decoded bytes to.
     */
    private static void decodeLine(String line, ByteArrayOutputStream out) {
        int length = line.charAt(0) - 32;
        if (length <= 0 || length > 45) {
            return;
        }

        ByteArrayOutputStream lineOut = new ByteArrayOutputStream(48);
        for (int i = 1; i < line.length(); i += 4) {
            int[] group = new int[4];
            for (int j = 0; j < 4; j++) {
                char c = (i + j) < line.length() ? line.charAt(i + j) : '`';
                group[j] = (c - 32) & 0x3F;
            }
            lineOut.write((group[0] << 2) | (group[1] >> 4));
            lineOut.write(((group[1] & 0x0F) << 4) | (group[2] >> 2));
            lineOut.write(((group[2] & 0x03) << 6) | group[3]);
        }

        byte[] lineBytes = lineOut.toByteArray();
        out.write(lineBytes, 0, Math.min(length, lineBytes.length));
    }

    private static String stripTrailingWhitespace(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }
}
