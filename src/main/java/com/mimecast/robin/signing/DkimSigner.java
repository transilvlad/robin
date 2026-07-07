package com.mimecast.robin.signing;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;

/**
 * DKIM signing backend interface.
 * <p>
 * Implementations sign an email file and return the {@code DKIM-Signature} header value
 * (without the {@code "DKIM-Signature: "} field name prefix) for a given domain and selector.
 * <p>
 * Built-in implementations:
 * <ul>
 *   <li>{@link RspamdDkimSigner} — delegates to Rspamd via HTTP (default)</li>
 *   <li>{@link NativeDkimSigner} — signs using Apache jDKIM without an external service</li>
 * </ul>
 * Custom implementations can be injected at runtime via {@code Factories.setDkimSigner()}.
 */
public interface DkimSigner {

    /**
     * Signs an email file for the given domain and selector.
     *
     * @param emailFile  Email file to sign (must exist and be readable).
     * @param domain     Signing domain ({@code d=} tag).
     * @param selector   DKIM selector ({@code s=} tag).
     * @param privateKey Base64-encoded PKCS8 private key (no PEM headers, no whitespace).
     * @return The {@code DKIM-Signature} header value (without field name), or empty if signing failed.
     * @throws IOException If the email file cannot be read.
     */
    Optional<String> sign(File emailFile, String domain, String selector, String privateKey) throws IOException;

    /**
     * Signs email content for the given domain and selector.
     * <p>Avoids re-reading the email from disk when signing with multiple domain/selector pairs.
     * <p>Default implementation writes the content to a temporary file and delegates to the
     * file variant, for custom implementations that predate this method.
     *
     * @param emailBytes Raw email content to sign.
     * @param domain     Signing domain ({@code d=} tag).
     * @param selector   DKIM selector ({@code s=} tag).
     * @param privateKey Base64-encoded PKCS8 private key (no PEM headers, no whitespace).
     * @return The {@code DKIM-Signature} header value (without field name), or empty if signing failed.
     * @throws IOException If signing fails due to I/O.
     */
    default Optional<String> sign(byte[] emailBytes, String domain, String selector, String privateKey) throws IOException {
        File temp = File.createTempFile("dkim-sign-", ".eml");
        try {
            Files.write(temp.toPath(), emailBytes);
            return sign(temp, domain, selector, privateKey);
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
    }
}
