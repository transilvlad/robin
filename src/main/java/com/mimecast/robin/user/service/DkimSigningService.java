package com.mimecast.robin.user.service;

import com.mimecast.robin.user.domain.DkimKey;
import com.mimecast.robin.user.domain.DkimKeyStatus;
import com.mimecast.robin.user.repository.DkimKeyRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Signs outbound emails with DKIM-Signature header(s) per RFC 6376.
 *
 * <p>Supports:
 * <ul>
 *   <li>RSA-2048 ({@code a=rsa-sha256}) via Bouncy Castle</li>
 *   <li>Ed25519 ({@code a=ed25519-sha256}) via Bouncy Castle</li>
 *   <li>Dual signing — two ACTIVE keys (one per algorithm) produce two headers</li>
 * </ul>
 *
 * <p>Canonicalization: {@code c=relaxed/simple} (relaxed header, simple body).
 *
 * <p>Private keys stored in {@code dkim_keys.private_key_enc} are AES-256-GCM encrypted
 * by {@link DkimPrivateKeyStore}. Pass {@code null} as {@code encryptionKey} to treat
 * {@code private_key_enc} as a raw PKCS#8 PEM (useful in tests).
 */
public class DkimSigningService {

    private static final Logger log = LogManager.getLogger(DkimSigningService.class);

    /** Headers signed in this order when present (From is mandatory per RFC 6376 §5.4). */
    private static final String[] CANDIDATE_HEADERS = {
            "from", "to", "subject", "date", "message-id", "mime-version", "content-type"
    };

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final DkimKeyRepository repository;
    private final DkimPrivateKeyStore keyStore;
    private final byte[] encryptionKey;

    /**
     * Creates a DkimSigningService.
     *
     * @param repository    DKIM key DAO — used to fetch ACTIVE keys per domain
     * @param encryptionKey AES-256 key for {@link DkimPrivateKeyStore#decrypt}, or
     *                      {@code null} to treat {@code private_key_enc} as raw PEM
     */
    public DkimSigningService(DkimKeyRepository repository, byte[] encryptionKey) {
        this.repository = repository;
        this.keyStore = new DkimPrivateKeyStore();
        this.encryptionKey = encryptionKey;
    }

    /**
     * Signs the raw email stream for the given sender domain.
     *
     * <p>Buffers the full message, computes DKIM-Signature header(s), and returns
     * a new stream with those headers prepended. Returns the original stream
     * unchanged when no ACTIVE key exists or on unrecoverable error.
     *
     * @param rawEmail     raw RFC 5322 email (headers + CRLF CRLF + body)
     * @param senderDomain MAIL FROM domain (e.g. {@code example.com})
     * @return signed InputStream
     * @throws IOException on read error
     */
    public InputStream sign(InputStream rawEmail, String senderDomain) throws IOException {
        List<DkimKey> activeKeys = repository.findByDomain(senderDomain).stream()
                .filter(k -> k.getStatus() == DkimKeyStatus.ACTIVE)
                .toList();

        if (activeKeys.isEmpty()) {
            log.debug("No active DKIM key for domain {}; outbound message unsigned", senderDomain);
            return rawEmail;
        }

        byte[] rawBytes = rawEmail.readAllBytes();
        List<String> sigHeaders = new ArrayList<>();

        for (DkimKey key : activeKeys) {
            try {
                sigHeaders.add(buildSignatureHeader(rawBytes, key, senderDomain));
                log.debug("DKIM signed domain={} selector={} algo={}", senderDomain, key.getSelector(), key.getAlgorithm());
            } catch (Exception e) {
                log.warn("DKIM signing failed domain={} selector={}: {}", senderDomain, key.getSelector(), e.getMessage());
            }
        }

        if (sigHeaders.isEmpty()) {
            return new ByteArrayInputStream(rawBytes);
        }

        byte[] sigBlock = String.join("", sigHeaders).getBytes(StandardCharsets.US_ASCII);
        return new SequenceInputStream(
                new ByteArrayInputStream(sigBlock),
                new ByteArrayInputStream(rawBytes));
    }

    // -------------------------------------------------------------------------
    // RFC 6376 signature computation
    // -------------------------------------------------------------------------

    private String buildSignatureHeader(byte[] rawBytes, DkimKey key, String domain) throws Exception {
        // 1. Locate the header/body split (\r\n\r\n)
        int splitPos = findSplit(rawBytes);
        String headerSection = new String(rawBytes, 0, splitPos, StandardCharsets.US_ASCII);
        byte[] bodyBytes = splitPos + 4 < rawBytes.length
                ? Arrays.copyOfRange(rawBytes, splitPos + 4, rawBytes.length)
                : new byte[0];

        // 2. Parse header fields (unfolded, lowercase names)
        List<String[]> parsedHeaders = parseHeaders(headerSection);

        // 3. Determine which headers to sign
        List<String> toSign = selectHeaders(parsedHeaders);

        // 4. Simple body canonicalization + SHA-256 body hash
        String canonBody = canonBodySimple(bodyBytes);
        String bh = sha256Base64(canonBody.getBytes(StandardCharsets.US_ASCII));

        // 5. Algorithm tag
        String algoTag = "ED25519".equals(key.getAlgorithm()) ? "ed25519-sha256" : "rsa-sha256";

        // 6. h= tag
        String hTag = String.join(":", toSign.stream().map(String::toLowerCase).toList());

        // 7. Partial DKIM-Signature value (b= is empty — to be filled after signing)
        String partialValue = "v=1; a=" + algoTag +
                "; c=relaxed/simple" +
                "; d=" + domain +
                "; s=" + key.getSelector() +
                "; h=" + hTag +
                "; bh=" + bh +
                "; b=";

        // 8. Data to sign: relaxed-canonical selected headers, then DKIM-Signature (no trailing CRLF)
        StringBuilder dataToSign = new StringBuilder();
        for (String name : toSign) {
            String value = findValue(parsedHeaders, name);
            if (value != null) {
                dataToSign.append(relaxedHeader(name, value)).append("\r\n");
            }
        }
        dataToSign.append(relaxedHeader("dkim-signature", partialValue)); // no trailing CRLF

        // 9. Sign
        byte[] sigBytes = sign(dataToSign.toString().getBytes(StandardCharsets.US_ASCII), key);
        String b = Base64.getEncoder().encodeToString(sigBytes);

        return "DKIM-Signature: " + partialValue + b + "\r\n";
    }

    // -------------------------------------------------------------------------
    // Header parsing and canonicalization
    // -------------------------------------------------------------------------

    /** Returns byte offset of the first {@code \r\n\r\n} sequence; returns data.length if not found. */
    private int findSplit(byte[] data) {
        for (int i = 0; i <= data.length - 4; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return data.length;
    }

    /**
     * Parses the header section into an ordered list of {@code [lowercase_name, raw_value]} pairs.
     * Folded header continuations are unfolded before splitting.
     */
    private List<String[]> parseHeaders(String section) {
        // Unfold: CRLF followed by WSP → single SP
        String unfolded = section.replaceAll("\r\n[ \t]+", " ");
        List<String[]> result = new ArrayList<>();
        for (String line : unfolded.split("\r\n")) {
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1);
            result.add(new String[]{name, value});
        }
        return result;
    }

    /** Returns headers from {@link #CANDIDATE_HEADERS} that are present; always includes {@code from}. */
    private List<String> selectHeaders(List<String[]> headers) {
        Set<String> present = new LinkedHashSet<>();
        for (String[] h : headers) present.add(h[0]);

        List<String> selected = new ArrayList<>();
        for (String name : CANDIDATE_HEADERS) {
            if (present.contains(name)) selected.add(name);
        }
        if (!selected.contains("from")) selected.add(0, "from");
        return selected;
    }

    /** Returns the value of the first header matching {@code name} (case-insensitive), or null. */
    private String findValue(List<String[]> headers, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String[] h : headers) {
            if (h[0].equals(lower)) return h[1];
        }
        return null;
    }

    /**
     * Relaxed header canonicalization (RFC 6376 §3.4.2).
     * Output: {@code lowercase_name:normalized_value} — NO trailing CRLF.
     */
    private String relaxedHeader(String name, String value) {
        String normName = name.toLowerCase(Locale.ROOT).strip();
        String normValue = value.replaceAll("[ \t]+", " ").strip();
        return normName + ":" + normValue;
    }

    // -------------------------------------------------------------------------
    // Body canonicalization and hashing
    // -------------------------------------------------------------------------

    /**
     * Simple body canonicalization (RFC 6376 §3.4.3).
     * Strips trailing blank lines; ensures non-empty body ends with {@code \r\n}.
     */
    private String canonBodySimple(byte[] bodyBytes) {
        if (bodyBytes.length == 0) return "";
        String body = new String(bodyBytes, StandardCharsets.US_ASCII);
        body = body.replaceAll("(\r\n)+$", "");
        return body.isEmpty() ? "" : body + "\r\n";
    }

    private String sha256Base64(byte[] data) {
        try {
            return Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // -------------------------------------------------------------------------
    // Private key loading and signing
    // -------------------------------------------------------------------------

    private byte[] sign(byte[] data, DkimKey key) throws GeneralSecurityException {
        String pem = (encryptionKey != null)
                ? keyStore.decrypt(key.getPrivateKeyEnc(), encryptionKey)
                : key.getPrivateKeyEnc();
        PrivateKey privateKey = loadPrivateKey(pem, key.getAlgorithm());
        String jcaAlgo = "ED25519".equals(key.getAlgorithm()) ? "Ed25519" : "SHA256withRSA";
        Signature sig = Signature.getInstance(jcaAlgo, "BC");
        sig.initSign(privateKey);
        sig.update(data);
        return sig.sign();
    }

    private PrivateKey loadPrivateKey(String pem, String algorithm) throws GeneralSecurityException {
        String base64 = pem
                .replaceAll("-----[A-Z ]+-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(base64);
        String jcaKeyType = "ED25519".equals(algorithm) ? "Ed25519" : "RSA";
        return KeyFactory.getInstance(jcaKeyType, "BC")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
