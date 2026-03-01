package com.mimecast.robin.user.service;

import com.mimecast.robin.user.domain.DkimKey;
import com.mimecast.robin.user.domain.DkimKeyStatus;
import com.mimecast.robin.user.domain.DkimStrategy;
import com.mimecast.robin.user.repository.DkimKeyRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class DkimSigningServiceTest {

    private static HikariDataSource ds;
    private static DkimKeyRepository repo;
    private static DkimKeyGenerator generator;

    @BeforeAll
    static void setup() throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:dkim_sign_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(2);
        ds = new HikariDataSource(cfg);

        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dkim_keys (
                        id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        domain                VARCHAR(253) NOT NULL,
                        selector              VARCHAR(63)  NOT NULL,
                        algorithm             VARCHAR(10)  NOT NULL,
                        private_key_enc       TEXT         NOT NULL,
                        public_key            TEXT         NOT NULL,
                        dns_record_value      TEXT         NOT NULL,
                        status                VARCHAR(20)  NOT NULL,
                        test_mode             BOOLEAN      DEFAULT TRUE,
                        strategy              VARCHAR(20),
                        service_tag           VARCHAR(63),
                        paired_key_id         BIGINT,
                        rotation_scheduled_at TIMESTAMP WITH TIME ZONE,
                        published_at          TIMESTAMP WITH TIME ZONE,
                        activated_at          TIMESTAMP WITH TIME ZONE,
                        retire_after          TIMESTAMP WITH TIME ZONE,
                        retired_at            TIMESTAMP WITH TIME ZONE,
                        created_at            TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT uq_dkim_sign_keys UNIQUE (domain, selector)
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dkim_rotation_events (
                        id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        key_id       BIGINT,
                        event_type   VARCHAR(30) NOT NULL,
                        old_status   VARCHAR(20),
                        new_status   VARCHAR(20),
                        notes        TEXT,
                        triggered_by VARCHAR(50),
                        created_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }

        repo = new DkimKeyRepository(ds);
        generator = new DkimKeyGenerator();
    }

    @AfterAll
    static void teardown() {
        if (ds != null) ds.close();
    }

    @BeforeEach
    void clearKeys() throws Exception {
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM dkim_rotation_events");
            stmt.execute("DELETE FROM dkim_keys");
        }
    }

    // -------------------------------------------------------------------------
    // No active key — stream returned unchanged
    // -------------------------------------------------------------------------

    @Test
    void noActiveKey_returnsOriginalStream() throws Exception {
        DkimSigningService service = new DkimSigningService(repo, null);
        byte[] raw = testEmail().getBytes(StandardCharsets.US_ASCII);

        InputStream result = service.sign(new ByteArrayInputStream(raw), "example.com");
        byte[] out = result.readAllBytes();
        assertArrayEquals(raw, out);
    }

    @Test
    void pendingKey_doesNotSign() throws Exception {
        insertKey("nosign.com", "sel1", "RSA_2048", generator.generateRsa2048(), DkimKeyStatus.PENDING_PUBLISH);
        DkimSigningService service = new DkimSigningService(repo, null);

        InputStream result = service.sign(asStream(testEmail()), "nosign.com");
        String output = new String(result.readAllBytes(), StandardCharsets.US_ASCII);
        assertFalse(output.contains("DKIM-Signature:"), "Pending key must not produce a signature");
    }

    // -------------------------------------------------------------------------
    // RSA-2048 signing
    // -------------------------------------------------------------------------

    @Test
    void rsaSigning_headerPresent() throws Exception {
        DkimKeyGenerator.GeneratedKey gk = generator.generateRsa2048();
        insertKey("rsa.com", "20260227", "RSA_2048", gk, DkimKeyStatus.ACTIVE);
        DkimSigningService service = new DkimSigningService(repo, null);

        String output = sign(service, testEmail(), "rsa.com");
        assertTrue(output.startsWith("DKIM-Signature:"), "Output must begin with DKIM-Signature header");
        assertTrue(output.contains("a=rsa-sha256"), "Must declare rsa-sha256 algorithm");
        assertTrue(output.contains("d=rsa.com"), "Must declare domain");
        assertTrue(output.contains("s=20260227"), "Must declare selector");
        assertTrue(output.contains("bh="), "Must include body hash");
        assertTrue(output.contains("b="), "Must include signature");
    }

    @Test
    void rsaSigning_signatureVerifies() throws Exception {
        DkimKeyGenerator.GeneratedKey gk = generator.generateRsa2048();
        DkimKey key = insertKey("verify.com", "sel-rsa", "RSA_2048", gk, DkimKeyStatus.ACTIVE);
        DkimSigningService service = new DkimSigningService(repo, null);

        String output = sign(service, testEmail(), "verify.com");
        assertSignatureVerifiable(output, gk.keyPair().getPublic(), "SHA256withRSA");
    }

    // -------------------------------------------------------------------------
    // Ed25519 signing
    // -------------------------------------------------------------------------

    @Test
    void ed25519Signing_headerPresent() throws Exception {
        DkimKeyGenerator.GeneratedKey gk = generator.generateEd25519();
        insertKey("ed.com", "sel-ed", "ED25519", gk, DkimKeyStatus.ACTIVE);
        DkimSigningService service = new DkimSigningService(repo, null);

        String output = sign(service, testEmail(), "ed.com");
        assertTrue(output.contains("a=ed25519-sha256"), "Must declare ed25519-sha256 algorithm");
    }

    @Test
    void ed25519Signing_signatureVerifies() throws Exception {
        DkimKeyGenerator.GeneratedKey gk = generator.generateEd25519();
        insertKey("ed-verify.com", "sel-ed", "ED25519", gk, DkimKeyStatus.ACTIVE);
        DkimSigningService service = new DkimSigningService(repo, null);

        String output = sign(service, testEmail(), "ed-verify.com");
        assertSignatureVerifiable(output, gk.keyPair().getPublic(), "Ed25519");
    }

    // -------------------------------------------------------------------------
    // Dual signing (Strategy 3: RSA + Ed25519 both ACTIVE)
    // -------------------------------------------------------------------------

    @Test
    void dualSigning_twoHeaders() throws Exception {
        insertKey("dual.com", "rsa-sel", "RSA_2048", generator.generateRsa2048(), DkimKeyStatus.ACTIVE);
        insertKey("dual.com", "ed-sel", "ED25519", generator.generateEd25519(), DkimKeyStatus.ACTIVE);
        DkimSigningService service = new DkimSigningService(repo, null);

        String output = sign(service, testEmail(), "dual.com");
        long count = Pattern.compile("DKIM-Signature:").matcher(output).results().count();
        assertEquals(2, count, "Dual-algorithm strategy must produce two DKIM-Signature headers");
        assertTrue(output.contains("a=rsa-sha256"), "Must contain RSA header");
        assertTrue(output.contains("a=ed25519-sha256"), "Must contain Ed25519 header");
    }

    // -------------------------------------------------------------------------
    // Signed content is preserved
    // -------------------------------------------------------------------------

    @Test
    void originalMessagePreservedAfterSigning() throws Exception {
        DkimKeyGenerator.GeneratedKey gk = generator.generateRsa2048();
        insertKey("preserve.com", "sel-p", "RSA_2048", gk, DkimKeyStatus.ACTIVE);
        DkimSigningService service = new DkimSigningService(repo, null);

        String email = testEmail();
        String output = sign(service, email, "preserve.com");

        // The original email bytes must appear at the end of the output
        assertTrue(output.endsWith(email), "Original email content must be preserved verbatim");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private DkimKey insertKey(String domain, String selector, String algorithm,
                               DkimKeyGenerator.GeneratedKey gk, DkimKeyStatus status) {
        DkimKey key = new DkimKey();
        key.setDomain(domain);
        key.setSelector(selector);
        key.setAlgorithm(algorithm);
        // Store private key PEM directly (no encryption — encryptionKey=null in service)
        key.setPrivateKeyEnc(gk.privateKeyPem());
        key.setPublicKey(gk.publicKeyPem());
        key.setDnsRecordValue("v=DKIM1; k=rsa; p=" + gk.dnsPublicKey());
        key.setStatus(status);
        key.setTestMode(false);
        key.setStrategy(DkimStrategy.MANUAL);
        return repo.save(key);
    }

    private String testEmail() {
        return "From: sender@example.com\r\n" +
               "To: recipient@example.org\r\n" +
               "Subject: DKIM Test Message\r\n" +
               "Date: Thu, 27 Feb 2026 12:00:00 +0000\r\n" +
               "Message-ID: <test-dkim-001@example.com>\r\n" +
               "MIME-Version: 1.0\r\n" +
               "Content-Type: text/plain\r\n" +
               "\r\n" +
               "Hello, this is a DKIM-signed test email.\r\n";
    }

    private InputStream asStream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }

    private String sign(DkimSigningService service, String email, String domain) throws Exception {
        InputStream result = service.sign(asStream(email), domain);
        return new String(result.readAllBytes(), StandardCharsets.US_ASCII);
    }

    /**
     * Extracts the {@code b=} value from a DKIM-Signature header and verifies it
     * against the reconstructed data-to-sign using the provided public key.
     *
     * <p>This re-implements the verifier's perspective: it recomputes the signed
     * data (selected headers + DKIM-Signature with empty b=) and checks the signature.
     */
    private void assertSignatureVerifiable(String output, PublicKey publicKey, String jcaAlgo) throws Exception {
        // Extract the first DKIM-Signature header value (possibly multi-line up to the next header)
        Pattern sigPattern = Pattern.compile("DKIM-Signature: ([^\r]+(?:\r\n[ \t][^\r]+)*)");
        Matcher m = sigPattern.matcher(output);
        assertTrue(m.find(), "DKIM-Signature header must be present");
        String sigHeaderValue = m.group(1).replaceAll("\r\n[ \t]", " ").strip();

        // Extract b= value
        Pattern bPattern = Pattern.compile("; b=([A-Za-z0-9+/=]+)");
        Matcher bm = bPattern.matcher(sigHeaderValue);
        assertTrue(bm.find(), "b= tag must be present");
        byte[] signature = Base64.getDecoder().decode(bm.group(1));

        // Find where original email begins (after DKIM-Signature headers)
        int originalStart = output.indexOf("From:");
        assertTrue(originalStart >= 0, "Original From: header must be present");
        String originalEmail = output.substring(originalStart);

        // Parse headers and body from original email
        int splitPos = originalEmail.indexOf("\r\n\r\n");
        String headerSection = originalEmail.substring(0, splitPos);
        String bodyRaw = originalEmail.substring(splitPos + 4);

        // Simple body canon
        String body = bodyRaw.replaceAll("(\r\n)+$", "") + "\r\n";
        byte[] bodyHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.US_ASCII));
        String bh = Base64.getEncoder().encodeToString(bodyHash);

        // Verify bh= tag matches
        assertTrue(sigHeaderValue.contains("bh=" + bh), "bh= must match body hash");

        // Reconstruct partial DKIM-Signature (b= stripped)
        String partialSig = sigHeaderValue.replaceAll("; b=[A-Za-z0-9+/=]*$", "; b=");

        // Extract h= tag and selected headers
        Pattern hPattern = Pattern.compile("h=([^;]+)");
        Matcher hm = hPattern.matcher(sigHeaderValue);
        assertTrue(hm.find(), "h= tag must be present");
        String[] hNames = hm.group(1).strip().split(":");

        // Unfold headers
        String unfolded = headerSection.replaceAll("\r\n[ \t]+", " ");
        java.util.Map<String, String> headerMap = new java.util.LinkedHashMap<>();
        for (String line : unfolded.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).strip().toLowerCase();
            String value = line.substring(colon + 1).strip();
            headerMap.putIfAbsent(name, value);
        }

        // Rebuild data-to-sign
        StringBuilder dataToSign = new StringBuilder();
        for (String hName : hNames) {
            String hVal = headerMap.get(hName.strip().toLowerCase());
            if (hVal != null) {
                dataToSign.append(hName.strip().toLowerCase()).append(":").append(hVal).append("\r\n");
            }
        }
        dataToSign.append("dkim-signature:").append(partialSig.strip());

        // Verify
        Signature verifier = Signature.getInstance(jcaAlgo, "BC");
        verifier.initVerify(publicKey);
        verifier.update(dataToSign.toString().getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(signature), "DKIM signature must verify against the public key");
    }
}
