package com.mimecast.robin.bots;

import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mx.util.LocalDnsResolver;
import com.mimecast.robin.smtp.MessageEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Message;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class EmailAnalysisProvenanceTest {
    private static final String HEADERS = "From: first@origin.test\r\n"
            + "Authentication-Results: untrusted.example.test; spf=pass; dkim=pass; dmarc=pass\r\n"
            + "authentication-results: conflicting.example.test; dkim=fail\r\n"
            + "DKIM-Signature: v=1; d=other.test; s=example; b=unverified\r\n"
            + "dkim-signature: v=1; d=origin.test; s=example; b=unverified\r\n\r\nBody";

    @Test
    void incomingClaimsAreOrderedInformationalEvidenceNotScannerResults() throws Exception {
        try (EmailParser parser = parser()) {
            var ctx = EmailAnalysisBot.MessageContext.from(null, new MessageEnvelope(), parser);
            assertEquals(2, ctx.headers("Authentication-Results").size());
            assertTrue(ctx.headers("Authentication-Results").getFirst().startsWith("untrusted.example.test"));
            assertEquals(List.of("other.test", "origin.test"),
                    ctx.dkimSignatures().stream().map(EmailAnalysisBot.DkimSignature::domain).toList());
            for (String check : List.of("checkSpf", "checkDkim", "checkDmarc")) {
                assertEquals("SKIPPED", String.valueOf(check(check, ctx).status()));
            }
            assertEquals("INFO", String.valueOf(check("checkAuthenticationResults", ctx).status()));
        }
    }

    @Test
    void onlyTrustedScannerSymbolsDecideAuthenticationDespiteConflictingIncomingClaims() throws Exception {
        try (EmailParser parser = parser()) {
            MessageEnvelope envelope = new MessageEnvelope();
            envelope.addScanResult(Map.of("scanner", "not-rspamd", "symbols",
                    Map.of("R_DKIM_ALLOW", Map.of())));
            var untrusted = EmailAnalysisBot.MessageContext.from(null, envelope, parser);
            assertEquals("SKIPPED", String.valueOf(check("checkDkim", untrusted).status()));
            envelope.addScanResult(Map.of("scanner", "rspamd", "symbols", Map.of(
                    "R_SPF_FAIL", Map.of(), "R_DKIM_REJECT", Map.of(), "DMARC_POLICY_REJECT", Map.of())));
            var rejected = EmailAnalysisBot.MessageContext.from(null, envelope, parser);
            for (String name : List.of("checkSpf", "checkDkim", "checkDmarc")) {
                assertEquals("FAIL", String.valueOf(check(name, rejected).status()));
            }
            MessageEnvelope accepted = new MessageEnvelope();
            accepted.addScanResult(Map.of("scanner", "rspamd", "symbols", Map.of(
                    "R_SPF_ALLOW", Map.of(), "R_DKIM_ALLOW", Map.of(), "DMARC_POLICY_ALLOW", Map.of())));
            var trusted = EmailAnalysisBot.MessageContext.from(null, accepted, parser);
            for (String name : List.of("checkSpf", "checkDkim", "checkDmarc")) {
                assertEquals("PASS", String.valueOf(check(name, trusted).status()));
            }
        }
    }

    @Test
    void claimedAlignmentNeverAuthenticatesAnUnverifiedAlignedSignature() throws Exception {
        var originalResolver = Lookup.getDefaultResolver();
        Lookup.setDefaultResolver(new LocalDnsResolver() {
            @Override
            public CompletionStage<Message> sendAsync(Message query) {
                return CompletableFuture.completedFuture(send(query));
            }

            @Override
            public CompletionStage<Message> sendAsync(Message query, Executor executor) {
                return sendAsync(query);
            }
        });
        try (EmailParser parser = parser()) {
            for (String symbol : List.of("R_DKIM_REJECT", "R_DKIM_ALLOW")) {
                MessageEnvelope envelope = new MessageEnvelope().setMail("sender@other.test");
                envelope.addScanResult(Map.of("scanner", "rspamd", "symbols", Map.of(symbol, Map.of())));
                var ctx = EmailAnalysisBot.MessageContext.from(null, envelope, parser);
                var result = check("checkDmarcAlignment", ctx);
                assertEquals("INFO", String.valueOf(result.status()));
                assertTrue(result.summary().contains("not authentication"));
            }
        } finally {
            Lookup.setDefaultResolver(originalResolver);
        }
    }

    private static EmailAnalysisBot.CheckResult check(String name, EmailAnalysisBot.MessageContext ctx)
            throws Exception {
        var method = EmailAnalysisBot.class.getDeclaredMethod(name, EmailAnalysisBot.MessageContext.class);
        method.setAccessible(true);
        return (EmailAnalysisBot.CheckResult) method.invoke(new EmailAnalysisBot(), ctx);
    }

    private static EmailParser parser() throws Exception {
        return new EmailParser(new ByteArrayInputStream(HEADERS.getBytes(StandardCharsets.US_ASCII))).parse(true);
    }
}
