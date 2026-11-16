package com.mimecast.robin.scanners;

import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.RefCountedFileMessageSource;
import com.mimecast.robin.smtp.io.LineInputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Applies trusted scanner header changes without reserializing signed MIME bodies.
 */
public final class RspamdMessageMutations {
    private record Header(String name, byte[] raw) {
    }

    private record Addition(String name, String value, int order) {
    }

    private RspamdMessageMutations() {
    }

    /**
     * Selects existing headers to remove by lower-case name and value.
     */
    @FunctionalInterface
    public interface Removal {
        boolean test(String name, String value);
    }

    public static boolean apply(MessageEnvelope envelope, Map<String, Object> result) throws IOException {
        return apply(envelope, result, List.of(), (name, value) -> false);
    }

    /**
     * Applies the scanner's changes and Robin's own headers in one rewrite of the header block.
     *
     * @param envelope Envelope whose message is rewritten.
     * @param result   Scanner result; may be empty.
     * @param prepend  Name and value pairs to prepend, in order.
     * @param removal  Selects existing headers to remove before prepending.
     * @return Whether a fresh DKIM signature was added.
     * @throws IOException When the message cannot be read or a change is not valid.
     */
    public static boolean apply(MessageEnvelope envelope, Map<String, Object> result,
                                List<String[]> prepend, Removal removal) throws IOException {
        Map<?, ?> milter = result.get("milter") instanceof Map<?, ?> value ? value : Map.of();
        for (String unsupported : List.of("change_from", "add_rcpt", "del_rcpt")) {
            if (milter.containsKey(unsupported)) {
                throw new IOException("Rspamd requested unsupported envelope mutation");
            }
        }
        List<Addition> additions = new ArrayList<>();
        if (milter.get("add_headers") instanceof Map<?, ?> headers) {
            for (var entry : headers.entrySet()) {
                add(additions, entry.getKey().toString(), entry.getValue());
            }
        }
        Object signature = result.get("dkim-signature");
        if (signature instanceof String value) {
            add(additions, "DKIM-Signature", value);
        }
        if ("rewrite subject".equals(result.get("action"))) {
            if (!(result.get("subject") instanceof String subject)) {
                throw new IOException("Rspamd subject rewrite has no subject");
            }
            add(additions, "Subject", subject);
        }
        if ("add header".equals(result.get("action"))
                && additions.stream().noneMatch(header -> header.name().equalsIgnoreCase("X-Spam"))) {
            add(additions, "X-Spam", "Yes");
        }
        Map<?, ?> removals = milter.get("remove_headers") instanceof Map<?, ?> value ? value : Map.of();
        List<Addition> own = new ArrayList<>();
        for (int i = 0; i < prepend.size(); i++) {
            // Order i keeps them in the given order at the top of the header block.
            add(own, prepend.get(i)[0], Map.of("value", prepend.get(i)[1], "order", i));
        }
        if (additions.isEmpty() && removals.isEmpty() && own.isEmpty()) {
            return false;
        }
        boolean signed = additions.stream().anyMatch(header ->
                header.name().equalsIgnoreCase("DKIM-Signature") && !header.value().isBlank());
        rewrite(envelope, headers -> {
            for (var entry : removals.entrySet()) {
                String name = entry.getKey().toString();
                validateName(name);
                if (!(entry.getValue() instanceof Number index)) {
                    throw new IOException("Invalid Rspamd header removal");
                }
                remove(headers, name, index.intValue());
            }
            if ("rewrite subject".equals(result.get("action"))) {
                remove(headers, "Subject", 0);
            }
            insert(headers, additions);
            headers.removeIf(header -> {
                String text = new String(header.raw(), StandardCharsets.ISO_8859_1);
                return removal.test(header.name(), text.substring(text.indexOf(':') + 1).replaceAll("\\r?\\n$", "").trim());
            });
            insert(headers, own);
        });
        return signed;
    }

    @FunctionalInterface
    private interface HeaderEdit {
        void apply(List<Header> headers) throws IOException;
    }

    private static void insert(List<Header> headers, List<Addition> additions) {
        for (Addition addition : additions) {
            byte[] bytes = (addition.name() + ": " + addition.value() + "\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            int index = addition.order() < 0 ? headers.size() : Math.min(addition.order(), headers.size());
            headers.add(index, new Header(addition.name(), bytes));
        }
    }

    private static void rewrite(MessageEnvelope envelope, HeaderEdit edit) throws IOException {
        Path destination = envelope.getFile() == null ? Path.of(System.getProperty("java.io.tmpdir"))
                : Path.of(envelope.getFile()).toAbsolutePath().getParent();
        Files.createDirectories(destination);
        Path rewritten = Files.createTempFile(destination, "rspamd-", ".eml");
        boolean published = false;
        try {
            try (LineInputStream input = new LineInputStream(envelope.openMessageStream(), 8192);
                 var output = Files.newOutputStream(rewritten)) {
                List<Header> headers = readHeaders(input);
                edit.apply(headers);
                for (Header header : headers) {
                    output.write(header.raw());
                }
                output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                input.transferTo(output);
            }
            var oldSource = envelope.getMessageSource();
            envelope.setMessageSource(new RefCountedFileMessageSource(rewritten));
            published = true;
            if (oldSource != null) {
                oldSource.release();
            }
        } finally {
            if (!published) {
                Files.deleteIfExists(rewritten);
            }
        }
    }

    private static void add(List<Addition> additions, String name, Object entry) throws IOException {
        validateName(name);
        if (entry instanceof List<?> entries) {
            for (Object value : entries) {
                add(additions, name, value);
            }
            return;
        }
        Object raw = entry instanceof Map<?, ?> header ? header.get("value") : entry;
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IOException("Invalid Rspamd header value");
        }
        String normalized = value.replace("\r\n", "\n");
        if (normalized.contains("\r") || normalized.indexOf('\0') >= 0
                || normalized.matches("(?s).*\\n(?![ \\t]).*")) {
            throw new IOException("Invalid Rspamd header folding");
        }
        int order = entry instanceof Map<?, ?> header && header.get("order") instanceof Number number
                ? number.intValue() : 0;
        additions.add(new Addition(name, normalized.replace("\n", "\r\n"), order));
    }

    private static void validateName(String name) throws IOException {
        if (!MimeHeader.isValidName(name) || name.equalsIgnoreCase("From") || name.equalsIgnoreCase("Sender")) {
            throw new IOException("Unsupported Rspamd header mutation");
        }
    }

    private static List<Header> readHeaders(LineInputStream input) throws IOException {
        List<Header> headers = new ArrayList<>();
        ByteArrayOutputStream current = new ByteArrayOutputStream();
        String name = null;
        int length = 0;
        byte[] line;
        while ((line = input.readLine()) != null) {
            length += line.length;
            if (length > 1024 * 1024) {
                throw new IOException("Message headers exceed scanner rewrite limit");
            }
            String text = new String(line, StandardCharsets.ISO_8859_1);
            if (text.equals("\r\n") || text.equals("\n")) {
                if (name != null) headers.add(new Header(name, current.toByteArray()));
                return headers;
            }
            if (line[0] != ' ' && line[0] != '\t') {
                if (name != null) headers.add(new Header(name, current.toByteArray()));
                current.reset();
                int colon = text.indexOf(':');
                if (colon < 1) throw new IOException("Malformed message header");
                name = text.substring(0, colon).toLowerCase(Locale.ROOT);
            } else if (name == null) {
                throw new IOException("Header continuation has no field");
            }
            current.write(line);
        }
        throw new IOException("Message has no header/body boundary");
    }

    private static void remove(List<Header> headers, String name, int order) {
        List<Header> matching = headers.stream().filter(header -> header.name().equalsIgnoreCase(name)).toList();
        if (order == 0) {
            headers.removeAll(matching);
        } else {
            int index = order > 0 ? order - 1 : matching.size() + order;
            if (index >= 0 && index < matching.size()) {
                headers.remove(matching.get(index));
            }
        }
    }
}
