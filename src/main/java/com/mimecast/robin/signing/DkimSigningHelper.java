package com.mimecast.robin.signing;

import com.mimecast.robin.config.server.RspamdConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.scanners.DkimSigningLookup;
import com.mimecast.robin.smtp.MessageEnvelope;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Applies DKIM signatures to outbound {@link RelaySession} envelope files.
 * <p>
 * Every code path that enqueues an outbound message to the persistent relay queue must
 * invoke {@link #applyDkimSignaturesIfEnabled(RelaySession)} before enqueueing, so that
 * the signature is baked into the file that the queue processor later delivers. Retries
 * of a queued file therefore transmit the already-signed bytes unchanged.
 * <p>
 * There are two such call sites today:
 * <ul>
 *   <li>{@code RelayMessage.deliver()} — messages accepted over SMTP with an outbound
 *       {@link com.mimecast.robin.smtp.session.Session}.</li>
 *   <li>{@code BotHelper.queueBotResponse()} — bot-generated responses that construct
 *       an outbound session in-process rather than receiving one over SMTP.</li>
 * </ul>
 * Extracting this into a helper keeps the two paths from diverging (the bot path
 * previously bypassed signing entirely, so bot replies went out unsigned).
 */
public final class DkimSigningHelper {

    private static final Logger log = LogManager.getLogger(DkimSigningHelper.class);

    private DkimSigningHelper() {
    }

    /**
     * Signs each envelope file in the session (in place, on disk) with every configured
     * (domain, selector) pair returned by {@link DkimSigningLookup} for the envelope's
     * sender domain. Idempotent per call: skips when DKIM signing is not enabled, when
     * no signing key is configured, when the sender domain has no lookup rows, or when
     * the envelope has no file.
     *
     * @param relaySession Session whose envelope files should be signed prior to enqueue.
     */
    public static void applyDkimSignaturesIfEnabled(RelaySession relaySession) {
        RspamdConfig rspamdConfig = Config.getServer().getRspamd();
        RspamdConfig.DkimSigningConfig signingConfig = rspamdConfig.getDkimSigning();
        if (!signingConfig.isEnabled()) return;

        String keyPathTemplate = signingConfig.getKeyPath();
        if (keyPathTemplate.isEmpty()) {
            log.warn("DKIM signing enabled but keyPath not configured; skipping signing");
            return;
        }

        // Resolve signer: plugin override takes precedence over config-based backend selection.
        DkimSigner signer = Factories.getDkimSigner();
        if (signer == null) {
            if ("native".equalsIgnoreCase(signingConfig.getBackend())) {
                signer = new NativeDkimSigner();
            } else {
                signer = new RspamdDkimSigner(rspamdConfig.getHost(), rspamdConfig.getPort());
            }
        }

        for (MessageEnvelope envelope : relaySession.getSession().getEnvelopes()) {
            if (envelope.getFile() == null) continue;
            File emailFile = new File(envelope.getFile());
            if (!emailFile.exists()) continue;

            String senderDomain = extractSenderDomain(envelope.getMail());
            if (senderDomain == null) continue;

            List<String[]> signingOptions = DkimSigningLookup.getInstance(signingConfig).lookup(senderDomain);
            log.debug("DKIM signing: {} option(s) for domain {}", signingOptions.size(), senderDomain);

            if (signingOptions.isEmpty()) continue;

            // Read the email once and sign all domain/selector pairs from the same buffer.
            byte[] emailBytes;
            try {
                emailBytes = Files.readAllBytes(emailFile.toPath());
            } catch (IOException e) {
                log.error("Cannot read email file for DKIM signing {}: {}", emailFile.getName(), e.getMessage());
                continue;
            }

            List<String> signatures = new ArrayList<>();
            for (String[] opt : signingOptions) {
                String domain = opt[0];
                String selector = opt[1];
                String keyPath = keyPathTemplate.replace("$domain", domain).replace("$selector", selector);
                try {
                    String privateKey = readPrivateKey(keyPath);
                    Optional<String> sig = signer.sign(emailBytes, domain, selector, privateKey);
                    if (sig.isPresent()) {
                        signatures.add(sig.get());
                        log.debug("DKIM signature obtained: domain={} selector={}", domain, selector);
                    } else {
                        log.warn("No DKIM signature returned: domain={} selector={}", domain, selector);
                    }
                } catch (IOException e) {
                    log.warn("Cannot read DKIM key at {}: {}", keyPath, e.getMessage());
                }
            }

            if (!signatures.isEmpty()) {
                try {
                    prependDkimSignatures(emailFile, emailBytes, signatures);
                } catch (IOException e) {
                    log.error("Failed to prepend DKIM signatures to {}: {}", emailFile.getName(), e.getMessage());
                }
            }
        }
    }

    /**
     * Reads a PKCS8 PEM private key file and returns the base64 content without PEM headers.
     *
     * @param keyPath Path to the PEM key file.
     * @return Base64 key content (no PEM headers/footers, single line).
     * @throws IOException If the file cannot be read.
     */
    private static String readPrivateKey(String keyPath) throws IOException {
        return Files.readString(Path.of(keyPath)).lines()
                .filter(line -> !line.startsWith("-----"))
                .collect(Collectors.joining());
    }

    /**
     * Prepends {@code DKIM-Signature} headers to the email file.
     *
     * @param emailFile  Email file to modify in place.
     * @param original   Original email content (already read from the file).
     * @param signatures List of DKIM-Signature header values (already RFC 5322 folded by Rspamd).
     * @throws IOException If the file cannot be written.
     */
    private static void prependDkimSignatures(File emailFile, byte[] original, List<String> signatures) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String sig : signatures) {
            sb.append("DKIM-Signature: ").append(sig).append("\r\n");
        }
        byte[] headers = sb.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] combined = new byte[headers.length + original.length];
        System.arraycopy(headers, 0, combined, 0, headers.length);
        System.arraycopy(original, 0, combined, headers.length, original.length);
        Files.write(emailFile.toPath(), combined);
        log.debug("Prepended {} DKIM-Signature header(s) to {}", signatures.size(), emailFile.getName());
    }

    /**
     * Extracts the domain part from an email address.
     *
     * @param mail Email address (e.g., {@code "user@example.com"}).
     * @return Domain, or null if no {@code @} is present.
     */
    private static String extractSenderDomain(String mail) {
        if (mail == null) return null;
        int at = mail.lastIndexOf('@');
        return at >= 0 && at < mail.length() - 1 ? mail.substring(at + 1) : null;
    }
}
