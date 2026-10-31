package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.ConfigLoader;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class SubmissionLimitsTest {
    private final AtomicLong time = new AtomicLong();
    private final SubmissionLimits limits = new SubmissionLimits(time::get, 100);

    @BeforeEach void init() throws Exception { ConfigLoader.load("src/test/resources/cfg/"); }
    @AfterEach void restore() throws Exception { ConfigLoader.load("src/test/resources/cfg/"); }

    private void policy(Map<String, Object> account, Map<String, Object> domain) {
        Config.getServer().getMap().put("submissionLimits",
                Map.of("enabled", true, "windowSeconds", 60L, "account", account, "domain", domain));
    }

    @Test
    void accountAndDomainBudgetsAreIndependentOfSharedPeerIpAndChargesAreAtomic() {
        policy(Map.of("messages", 2L), Map.of("messages", 3L));
        assertTrue(limits.consume("Alice@Example.com", SubmissionLimits.Resource.MESSAGES, 2));
        assertFalse(limits.consume("alice@example.com", SubmissionLimits.Resource.MESSAGES, 1));
        assertTrue(limits.consume("bob@example.com", SubmissionLimits.Resource.MESSAGES, 1));
        assertFalse(limits.consume("bob@example.com", SubmissionLimits.Resource.MESSAGES, 1));
        assertTrue(limits.consume("alice@other.example", SubmissionLimits.Resource.MESSAGES, 1));
    }

    @Test
    void recipientAndByteLimitsIncludeExactBoundaryAndRejectedChargesConsumeNeitherBudget() {
        policy(Map.of("bytes", 8L, "recipients", 3L), Map.of("bytes", 12L));
        assertTrue(limits.consume("alice@example.com", SubmissionLimits.Resource.BYTES, 8));
        assertFalse(limits.consume("alice@example.com", SubmissionLimits.Resource.BYTES, 1));
        assertTrue(limits.consume("bob@example.com", SubmissionLimits.Resource.BYTES, 4));
        assertFalse(limits.consume("bob@example.com", SubmissionLimits.Resource.BYTES, 1));
        policy(Map.of("bytes", 8L, "recipients", 3L), Map.of());
        assertTrue(limits.consume("bob@example.com", SubmissionLimits.Resource.BYTES, 4));
        assertTrue(limits.consume("alice@example.com", SubmissionLimits.Resource.RECIPIENTS, 3));
        assertFalse(limits.consume("alice@example.com", SubmissionLimits.Resource.RECIPIENTS, 1));
    }

    @Test
    void concurrentSessionsAreReleasedAndWindowResetDoesNotForgetActiveSessions() {
        policy(Map.of("concurrentConnections", 1L), Map.of("concurrentConnections", 2L));
        assertTrue(limits.admit("alice@example.com", "a1"));
        assertTrue(limits.admit("alice@example.com", "a1"));
        assertFalse(limits.admit("alice@example.com", "a2"));
        assertTrue(limits.admit("bob@example.com", "b1"));
        assertFalse(limits.admit("carol@example.com", "c1"));
        time.set(TimeUnit.SECONDS.toNanos(60));
        assertFalse(limits.admit("alice@example.com", "a2"));
        limits.release("a1");
        assertTrue(limits.admit("carol@example.com", "c1"));
        limits.release("a1");
        assertFalse(limits.admit("carol@example.com", "c2"));
    }

    @Test
    void authFailureThrottleOnlyLocksTheNamedAccountAndRecoversAtWindowBoundary() {
        policy(Map.of("authFailures", 2L), Map.of());
        limits.recordFailure("alice@example.com");
        assertTrue(limits.canAuthenticate("alice@example.com"));
        limits.recordFailure("ALICE@EXAMPLE.COM");
        assertFalse(limits.canAuthenticate("alice@example.com"));
        assertFalse(limits.admit("alice@example.com", "a1"));
        assertTrue(limits.canAuthenticate("bob@example.com"));
        time.set(TimeUnit.SECONDS.toNanos(60));
        assertTrue(limits.canAuthenticate("alice@example.com"));
    }

    @Test
    void concurrentDomainChargesCannotRacePastTheLimit() throws Exception {
        policy(Map.of(), Map.of("messages", 1L));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return limits.consume("alice@example.com", SubmissionLimits.Resource.MESSAGES, 1); });
            var b = executor.submit(() -> { start.await(); return limits.consume("bob@example.com", SubmissionLimits.Resource.MESSAGES, 1); });
            start.countDown();
            assertNotEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void boundedTrackingFailsExplicitlyAndReclaimsExpiredInactiveKeys() {
        policy(Map.of("messages", 1L), Map.of());
        var bounded = new SubmissionLimits(time::get, 2);
        assertTrue(bounded.consume("alice@example.com", SubmissionLimits.Resource.MESSAGES, 1));
        assertThrows(IllegalStateException.class,
                () -> bounded.consume("bob@example.com", SubmissionLimits.Resource.MESSAGES, 1));
        time.set(TimeUnit.SECONDS.toNanos(60));
        assertTrue(bounded.consume("bob@example.com", SubmissionLimits.Resource.MESSAGES, 1));
    }

    @Test
    void unknownAuthNamesCannotExhaustVerifiedSubmissionTracker() {
        policy(Map.of("authFailures", 2L, "concurrentConnections", 1L), Map.of());
        var bounded = new SubmissionLimits(time::get, 2);
        bounded.recordFailure("unknown1@example.com");
        bounded.recordFailure("unknown2@example.com");
        assertThrows(IllegalStateException.class, () -> bounded.recordFailure("unknown3@example.com"));
        assertTrue(bounded.canAuthenticate("verified@example.com"));
        assertTrue(bounded.admit("verified@example.com", "verified-session"));
    }

    @Test
    void sessionTrackingIsBoundedEvenWhenConcurrentConnectionQuotasAreUnlimited() {
        policy(Map.of(), Map.of());
        var bounded = new SubmissionLimits(time::get, 2);
        assertTrue(bounded.admit("alice@example.com", "a1"));
        assertTrue(bounded.admit("alice@example.com", "a2"));
        assertThrows(IllegalStateException.class, () -> bounded.admit("alice@example.com", "a3"));
        bounded.release("a1");
        assertTrue(bounded.admit("alice@example.com", "a3"));
        assertThrows(IllegalArgumentException.class, () -> bounded.admit("alice@example.com", ""));
    }

    @Test
    void invalidPolicyIsNotUnlimitedAndDisabledDefaultsPreserveLegacyBehavior() {
        assertTrue(limits.consume("alice@example.com", SubmissionLimits.Resource.BYTES, Long.MAX_VALUE));
        policy(Map.of("messages", -1L), Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> limits.consume("alice@example.com", SubmissionLimits.Resource.MESSAGES, 1));
    }

    @Test
    void malformedFractionalOrUnknownSettingsCannotSilentlyDisableLimits() {
        for (var account : List.<Map<String, Object>>of(Map.of("bytes", 0.5), Map.of("bytes", "invalid"),
                Map.of("bytez", 1L), Map.of("bytes", "9223372036854775808"))) {
            policy(account, Map.of());
            assertThrows(IllegalArgumentException.class, () -> limits.canAuthenticate("alice@example.com"));
        }
        Config.getServer().getMap().put("submissionLimits", Map.of("enabled", "true"));
        assertThrows(IllegalArgumentException.class, () -> limits.canAuthenticate("alice@example.com"));
        Config.getServer().getMap().put("submissionLimits", Map.of("account", "invalid"));
        assertThrows(IllegalArgumentException.class, () -> limits.canAuthenticate("alice@example.com"));
    }

    @Test
    void uploadStreamChecksBytesBeforeWritingAndDoesNotDoubleChargeBulkWrites() throws Exception {
        policy(Map.of("bytes", 5L), Map.of());
        var connection = new ConnectionMock(new StringBuilder());
        connection.getSession().setUsername("stream-" + java.util.UUID.randomUUID() + "@example.com").setAuth(true);
        var received = new ByteArrayOutputStream();
        var stream = SubmissionLimits.receivingStream(connection, received);
        stream.write(new byte[]{1, 2, 3, 4, 5});
        assertThrows(IOException.class, () -> stream.write(6));
        assertEquals(5, received.size());
        stream.close();
    }
}
