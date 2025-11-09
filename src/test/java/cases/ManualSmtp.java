package cases;

import com.mimecast.robin.assertion.AssertException;
import com.mimecast.robin.main.Client;
import com.mimecast.robin.main.Foundation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.io.IOException;

/**
 * Manual SMTP test cases.
 */
@Disabled
public class ManualSmtp {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("cfg/");
    }

    /**
     * Manual test case for AV scanning testing.
     * ClamAV needs to be enabled.
     */
    @Test
    void inboundVirus() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/manual/inbound.virus.json5");
    }

    /**
     * Manual test case for Dovecot delivery testing.
     * Authentication/validation and saving to Dovecot need to be enabled.
     */
    @Test
    void inboundDovecot() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/manual/inbound.dovecot.json5");
    }

    /**
     * Manual test case for Dovecot submission testing.
     * Authentication/validation and saving to Dovecot need to be enabled.
     * Outbound relay should be disabled for this but if it's not
     * it's an interesting case for testing queue expiration and bounce expiration.
     */
    @Test
    void outboundDovecot() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/manual/outbound.dovecot.json5");
    }
}
