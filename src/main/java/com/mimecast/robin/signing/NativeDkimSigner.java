package com.mimecast.robin.signing;

import org.apache.james.jdkim.DKIMSigner;
import org.apache.james.jdkim.exceptions.FailException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

/**
 * DKIM signer using Apache jDKIM — no external service required.
 * <p>
 * Signs with {@code rsa-sha256} using relaxed/simple canonicalization.
 * The private key must be PKCS8 DER content encoded as base64 (no PEM
 * headers, no whitespace) — the same format stored on disk by {@code rspamadm dkim_keygen}
 * and read by {@link com.mimecast.robin.queue.relay.RelayMessage}.
 */
public class NativeDkimSigner implements DkimSigner {
    private static final Logger log = LogManager.getLogger(NativeDkimSigner.class);

    private static final String HEADER_PREFIX = "DKIM-Signature:";

    @Override
    public Optional<String> sign(File emailFile, String domain, String selector, String privateKey) throws IOException {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(privateKey);
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

            DKIMSigner signer = new DKIMSigner(
                    "v=1; a=rsa-sha256; c=relaxed/simple; d=" + domain + "; s=" + selector
                    + "; h=from:to:subject:date:message-id; bh=; b=", key);

            String result = signer.sign(Files.newInputStream(emailFile.toPath()));

            if (result == null) {
                return Optional.empty();
            }

            // jDKIM returns "DKIM-Signature:<value>" — strip the field name to return only the value.
            if (result.regionMatches(true, 0, HEADER_PREFIX, 0, HEADER_PREFIX.length())) {
                return Optional.of(result.substring(HEADER_PREFIX.length()).trim());
            }

            return Optional.of(result);
        } catch (FailException e) {
            log.warn("Native DKIM signing failed for domain={} selector={}: {}", domain, selector, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            throw new IOException("Native DKIM signing error for domain " + domain + ": " + e.getMessage(), e);
        }
    }
}
