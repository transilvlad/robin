package com.mimecast.robin.smtp;

import com.mimecast.robin.config.ConfigLoader;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.sasl.RecipientDirectory;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.extension.server.ServerAuth;
import com.mimecast.robin.smtp.extension.server.ServerData;
import com.mimecast.robin.smtp.extension.server.ServerMail;
import com.mimecast.robin.smtp.extension.server.ServerRcpt;
import com.mimecast.robin.smtp.security.SubmissionLimits;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.verb.Verb;
import com.mimecast.robin.storage.StorageProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class SubmissionPolicyTest {
    @TempDir Path directory;
    private List<Callable<StorageProcessor>> previousProcessors;

    @BeforeEach
    void init() throws Exception {
        previousProcessors = new ArrayList<>(Factories.getStorageProcessors());
        Foundation.init("src/test/resources/cfg/");
        Config.getServer().getStorage().getMap().put("path", directory.toString());
    }

    @AfterEach
    void restore() throws Exception {
        Factories.setStorageProcessors(previousProcessors);
        ConfigLoader.load("src/test/resources/cfg/");
    }

    private void policy(Map<String, Object> account) {
        Config.getServer().getMap().put("submissionLimits",
                Map.of("enabled", true, "account", account));
    }

    private ConnectionMock connection(String principal, String input) {
        var connection = new ConnectionMock(new StringBuilder(input));
        connection.setSocket(new Socket());
        connection.getSession().setDirection(EmailDirection.OUTBOUND).setAuth(true).setUsername(principal);
        connection.getSession().setFriendAddr("127.0.0.1");
        connection.getSession().setStartTls(true).setSecurePort(true);
        return connection;
    }

    private Verb auth(String principal, String password) {
        return new Verb("AUTH PLAIN " + Base64.getEncoder().encodeToString(
                ("\0" + principal + "\0" + password).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void verifiedAuthenticationRequiresAvailableConcurrencyAndDisconnectReleasesIt() throws Exception {
        policy(Map.of("concurrentConnections", 1L));
        var first = connection("", "QUIT\r\n");
        var second = connection("", "");
        var third = connection("", "");
        first.getSession().setAuth(false);
        second.getSession().setAuth(false);
        third.getSession().setAuth(false);
        try {
            assertTrue(new ServerAuth().process(first, auth("tony@example.com", "stark")));
            assertFalse(new ServerAuth().process(second, auth("tony@example.com", "stark")));
            assertFalse(second.getSession().isAuth());
            assertTrue(second.getOutput().contains("454 4.7.0"));
            new EmailReceipt(first).run();
            assertTrue(new ServerAuth().process(third, auth("tony@example.com", "stark")));
        } finally {
            SubmissionLimits.releaseConnection(first);
            SubmissionLimits.releaseConnection(third);
            second.close();
            third.close();
        }
    }

    @Test
    void failedPasswordThrottlesOnlyThatPrincipalNotOtherUsersBehindSameIp() throws Exception {
        policy(Map.of("authFailures", 1L));
        String principal = UUID.randomUUID() + "@example.com";
        var bad = connection("", "");
        var retry = connection("", "");
        var other = connection("", "");
        bad.getSession().setAuth(false);
        retry.getSession().setAuth(false);
        other.getSession().setAuth(false);
        try {
            assertFalse(new ServerAuth().process(bad, auth(principal, "wrong")));
            assertTrue(bad.getOutput().contains("535 "));
            assertFalse(new ServerAuth().process(retry, auth(principal, "wrong")));
            assertTrue(retry.getOutput().contains("454 "));
            assertTrue(new ServerAuth().process(other, auth("tony@example.com", "stark")));
        } finally {
            SubmissionLimits.releaseConnection(other);
            bad.close();
            retry.close();
            other.close();
        }
    }

    @Test
    void newMailAttemptsDoNotEvadeBudgetByChangingEnvelopeOrConnection() throws Exception {
        policy(Map.of("messages", 1L));
        String principal = UUID.randomUUID() + "@example.com";
        var first = connection(principal, "");
        var second = connection(principal, "");
        var other = connection(UUID.randomUUID() + "@example.com", "");
        var mail = new ServerMail();
        try {
            assertTrue(mail.process(first, new Verb("MAIL FROM:<sender@example.com>")));
            assertFalse(mail.process(second, new Verb("MAIL FROM:<another@example.com>")));
            assertTrue(second.getSession().getEnvelopes().isEmpty());
            assertTrue(second.getOutput().contains("451 4.7.0"));
            assertTrue(mail.process(other, new Verb("MAIL FROM:<sender@example.com>")));
        } finally {
            first.close();
            second.close();
            other.close();
        }
    }

    @Test
    void expandedAliasRecipientsMustFitQuotaBeforeEnvelopeMutation() throws Exception {
        policy(Map.of("recipients", 2L));
        Config.getServer().getMap().put("recipientRouting", Map.of("enabled", true));
        Config.getServer().getDovecot().getMap().put("saveLmtp", Map.of("enabled", true));
        var connection = connection(UUID.randomUUID() + "@example.com", "");
        connection.getSession().addEnvelope(new MessageEnvelope().setMail("sender@example.com"));
        var directory = new RecipientDirectory(new RecipientDirectory.Lookup() {
            @Override public Optional<RecipientDirectory.DomainPolicy> findDomain(String domain) {
                return Optional.of(new RecipientDirectory.DomainPolicy(true, false, null));
            }
            @Override public List<String> findAliases(String address) {
                return address.equals("team@example.com")
                        ? List.of("alice@example.com", "bob@example.com", "carol@example.com") : List.of();
            }
            @Override public boolean hasMailbox(String address) { return true; }
        }, false, 10, 100);
        var rcpt = new ServerRcpt() {
            @Override protected RecipientDirectory getRecipientDirectory() { return directory; }
        };
        try {
            assertFalse(rcpt.process(connection, new Verb("RCPT TO:<team@example.com>")));
            assertTrue(connection.getSession().getEnvelopes().getLast().getRcpts().isEmpty());
            assertTrue(connection.getOutput().contains("452 4.7.0"));
            var nextRcpt = new ServerRcpt() {
                @Override protected RecipientDirectory getRecipientDirectory() { return directory; }
            };
            assertTrue(nextRcpt.process(connection, new Verb("RCPT TO:<alice@example.com>")));
            assertEquals(List.of("alice@example.com"), connection.getSession().getEnvelopes().getLast().getRcpts());
        } finally {
            connection.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void dataAndBdatByteRejectionsNeverAcceptOrPersistIncludingBlackholes(boolean bdat, boolean blackhole) throws Exception {
        policy(Map.of("bytes", 1L));
        String content = "From: sender@example.com\r\n\r\nbody\r\n";
        var connection = connection(UUID.randomUUID() + "@example.com", content + (bdat ? "" : ".\r\n"));
        var envelope = new MessageEnvelope().setMail("sender@example.com").addRcpt("recipient@example.com");
        envelope.setBlackholed(blackhole);
        connection.getSession().addEnvelope(envelope);
        var saved = new AtomicBoolean();
        Factories.setStorageProcessors(List.of(() -> (conn, parser) -> { saved.set(true); return true; }));
        try {
            assertFalse(new ServerData().process(connection, new Verb(bdat
                    ? "BDAT " + content.getBytes(StandardCharsets.UTF_8).length + " LAST" : "DATA")));
            assertFalse(saved.get());
            assertFalse(connection.getOutput().contains("250 2.0.0"));
            assertTrue(connection.getOutput().contains("451 4.7.0"), connection.getOutput());
            assertTrue(connection.getSocket().isClosed());
        } finally {
            connection.getSession().close();
            connection.close();
        }
    }

    @Test
    void exactBdatByteBudgetExcludesServerReceivedHeader() throws Exception {
        String content = "From: sender@example.com\r\n\r\nbody\r\n";
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        policy(Map.of("bytes", (long) bytes));
        var connection = connection(UUID.randomUUID() + "@example.com", content);
        connection.getSession().addEnvelope(new MessageEnvelope().setMail("sender@example.com").addRcpt("recipient@example.com"));
        var saved = new AtomicBoolean();
        Factories.setStorageProcessors(List.of(() -> (conn, parser) -> {
            assertTrue(conn.getSession().getEnvelopes().getLast().readMessageBytes().length > bytes);
            saved.set(true);
            return true;
        }));
        try {
            assertTrue(new ServerData().process(connection, new Verb("BDAT " + bytes + " LAST")));
            assertTrue(saved.get());
        } finally {
            connection.getSession().close();
            connection.close();
        }
    }
}
