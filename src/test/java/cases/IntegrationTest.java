package cases;

import com.mimecast.robin.assertion.AssertException;
import com.mimecast.robin.main.Client;
import com.mimecast.robin.main.Foundation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Integration tests for Robin MTA email delivery and processing.
 *
 * <p>Run all eleven cases and the self-tests in the isolated LMTP, SQL-auth, ClamAV, and Rspamd stack:
 * {@code docker compose --project-directory .integration --profile test run --build --rm integration-tests}.
 *
 * <p>Run specific tests:
 * <ul>
 *   <li>{@code -Dtest=IntegrationTest#test01_basicSmtp} - Pass to the container runner for one test</li>
 *   <li>{@code -Dgroups=dovecot} - Select eight cases and the self-tests, excluding scanner tests 07, 08 and 08b</li>
 * </ul>
 */
@Tag("integration")
@Tag("suite")
@TestMethodOrder(MethodOrderer.MethodName.class)
public class IntegrationTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        String configDir = System.getProperty("robin.integration.clientConfig", ".integration/client-cfg");
        Path path = Path.of(configDir);
        for (String file : new String[]{"client.json5", "routes.json5", "server.json5", "properties.json5"}) {
            if (!Files.isRegularFile(path.resolve(file))) {
                throw new ConfigurationException("Missing integration client configuration: " + path.resolve(file));
            }
        }
        System.out.println("Integration client configuration: " + path.toAbsolutePath().normalize());
        Foundation.init(configDir);
    }

    @Test
    @Tag("dovecot")
    @DisplayName("01. Basic SMTP delivery test")
    void test01_basicSmtp() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/01-basic-smtp.json5");
    }

    @Test
    @Tag("dovecot")
    @DisplayName("02. Successful delivery with IMAP verification")
    void test02_deliverySuccess() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/02-delivery-success.json5");
    }

    @Test
    @Tag("dovecot")
    @DisplayName("03. Delivery to multiple recipients")
    void test03_deliveryMultipleRecipients() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/03-delivery-multiple-recipients.json5");
    }

    @Test
    @Tag("dovecot")
    @DisplayName("04. User not found rejection")
    void test04_userNotFound() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/04-user-not-found.json5");
    }

    @Test
    @Tag("dovecot")
    @DisplayName("05. Invalid sender rejection")
    void test05_invalidSender() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/05-invalid-sender.json5");
    }

    @Test
    @Tag("dovecot")
    @DisplayName("06. Partial delivery with mixed valid/invalid recipients")
    void test06_partialDeliveryMixed() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/06-partial-delivery-mixed.json5");
    }

    @Test
    @DisplayName("07. Spam detection with GTUBE pattern")
    void test07_spamGtube() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/07-spam-gtube.json5");
    }

    @Test
    @DisplayName("08. Virus detection with EICAR")
    void test08_virusEicar() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/08-virus-eicar.json5");
    }

    @Test
    @DisplayName("08b. Virus detection with EICAR in a MIME epilogue")
    void test08b_virusEicarEpilogue() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/08b-virus-eicar-epilogue.json5");
    }

    @Test
    @Tag("dovecot")
    @DisplayName("09. Chaos: LMTP failure queued and redelivered")
    void test09_chaosLmtpRetry() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/09-chaos-lmtp-retry.json5");
    }

    /**
     * Self-tests: cases that MUST fail, each with the reason it must fail for.
     *
     * <p>If one of these passes, an assertion type has stopped detecting failures and every
     * other case using it is suspect. The expected message guards against a case failing
     * for an unrelated reason, such as the stack being unreachable.
     */
    private static final Map<String, String> SELF_TESTS = new LinkedHashMap<>();

    static {
        SELF_TESTS.put("s01-wrong-reply-code.json5", "Assert unable to match [DATA] [^550]");
        SELF_TESTS.put("s02-log-verify-missing.json5", "did not match verify patterns");
        SELF_TESTS.put("s03-imap-wrong-subject.json5", "IMAP email verification failed: no header matched");
        SELF_TESTS.put("s04-imap-absent-but-delivered.json5", "IMAP email found when expected absent");
        SELF_TESTS.put("s05-imap-wrong-mailbox.json5", "IMAP email verification failed: no email found");
        SELF_TESTS.put("s06-log-refuse-matched.json5", "Found refuse pattern");
        SELF_TESTS.put("s07-protocol-none-matched.json5", "Assert matched refused [RCPT]");
        SELF_TESTS.put("s08-wrong-http-status.json5", "did not return status 404, got 200");
        SELF_TESTS.put("s09-log-verify-none-with-data.json5", "Request data found when none expected");
    }

    @TestFactory
    @Tag("dovecot")
    @DisplayName("Self-tests: assertions that must fail")
    Stream<DynamicTest> selfTests() throws IOException {
        Path folder = Path.of("src/test/resources/cases/config/integration/selftest");
        try (Stream<Path> files = Files.list(folder)) {
            Set<String> found = files.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
            Assertions.assertEquals(SELF_TESTS.keySet(), found, "Every self-test case needs an expected failure");
        }

        return SELF_TESTS.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            AssertException e = Assertions.assertThrows(AssertException.class,
                    () -> new Client().send(folder.resolve(entry.getKey()).toString()),
                    entry.getKey() + " passed but must fail");
            Assertions.assertTrue(e.getMessage() != null && e.getMessage().contains(entry.getValue()),
                    entry.getKey() + " failed for the wrong reason: " + e.getMessage());
        }));
    }

    @Test
    @Tag("dovecot")
    @DisplayName("10. Chaos: Storage processor failure")
    void test10_chaosStorageFailure() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/10-chaos-storage-failure.json5");
    }
}
