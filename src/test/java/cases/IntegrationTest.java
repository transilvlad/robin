package cases;

import com.mimecast.robin.assertion.AssertException;
import com.mimecast.robin.main.Client;
import com.mimecast.robin.main.Foundation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;

import javax.naming.ConfigurationException;
import java.io.IOException;

/**
 * Integration tests for Robin MTA email delivery and processing.
 *
 * <p>This test class supports two Docker Compose setups:
 * <ul>
 *   <li><b>Suite setup</b> (all 10 tests): {@code docker-compose -f docker-compose.suite.yaml up -d}<br>
 *       Multi-container with LMTP + SQL auth + ClamAV + Rspamd</li>
 *   <li><b>Dovecot setup</b> (8 tests, excluding 07-08): {@code docker-compose -f docker-compose.dovecot.yaml up -d}<br>
 *       Single-container with LDA + socket auth (no ClamAV/Rspamd)</li>
 * </ul>
 *
 * <p>Run specific tests:
 * <ul>
 *   <li>{@code mvn test -Dgroups="suite"} - Run all 10 tests (suite setup)</li>
 *   <li>{@code mvn test -Dgroups="dovecot"} - Run 8 tests: 01-06, 09-10 (dovecot setup)</li>
 * </ul>
 */
@Tag("integration")
@Tag("suite")
@TestMethodOrder(MethodOrderer.MethodName.class)
public class IntegrationTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("cfg/");
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
