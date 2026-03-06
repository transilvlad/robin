package com.mimecast.robin.signing;

import java.io.File;
import java.io.IOException;
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
}
