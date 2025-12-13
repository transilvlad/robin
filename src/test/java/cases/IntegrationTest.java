package cases;

import com.mimecast.robin.assertion.AssertException;
import com.mimecast.robin.main.Client;
import com.mimecast.robin.main.Foundation;
import org.junit.jupiter.api.*;

import javax.naming.ConfigurationException;
import java.io.IOException;

/**
 * Integration tests for full email suite of Robin MTA, Dovecot, ClamAV and Rspamd.
 *
 * <p>Prerequisites: docker-compose -f docker-compose.suite.yaml up -d
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
public class IntegrationTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("cfg/");
    }

    @Test
    @DisplayName("01. Basic SMTP delivery test")
    void test01_basicSmtp() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/01-basic-smtp.json5");
    }

    @Test
    @DisplayName("02. Successful delivery with IMAP verification")
    void test02_deliverySuccess() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/02-delivery-success.json5");
    }

    @Test
    @DisplayName("03. Delivery to multiple recipients")
    void test03_deliveryMultipleRecipients() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/03-delivery-multiple-recipients.json5");
    }

    @Test
    @DisplayName("04. User not found rejection")
    void test04_userNotFound() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/04-user-not-found.json5");
    }

    @Test
    @DisplayName("05. Invalid sender rejection")
    void test05_invalidSender() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/05-invalid-sender.json5");
    }

    @Test
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
    @DisplayName("09. Chaos: LDA delivery failure")
    void test09_chaosLdaFailure() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/09-chaos-lda-failure.json5");
    }

    @Test
    @DisplayName("10. Chaos: Storage processor failure")
    void test10_chaosStorageFailure() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/integration/10-chaos-storage-failure.json5");
    }
}
