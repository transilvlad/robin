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

/**
 * Integration tests for Robin MTA email delivery and processing.
 *
 * <p>Run all ten cases in the isolated LMTP, SQL-auth, ClamAV, and Rspamd stack:
 * {@code docker compose --project-directory .integration --profile test run --build --rm integration-tests}.
 *
 * <p>Run specific tests:
 * <ul>
 *   <li>{@code -Dtest=IntegrationTest#test01_basicSmtp} - Pass to the container runner for one test</li>
 *   <li>{@code -Dgroups=dovecot} - Select eight cases, excluding scanner tests 07-08</li>
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
    @Tag("dovecot")
    @DisplayName("09. Chaos: LDA delivery failure")
    void test09_chaosLdaFailure() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/09-chaos-lda-failure.json5");
    }

    @Test
    @Tag("dovecot")
    @DisplayName("10. Chaos: Storage processor failure")
    void test10_chaosStorageFailure() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/10-chaos-storage-failure.json5");
    }
}
