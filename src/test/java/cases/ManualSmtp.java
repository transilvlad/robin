package cases;

import com.mimecast.robin.assertion.AssertException;
import com.mimecast.robin.main.Client;
import com.mimecast.robin.main.Foundation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.io.IOException;

/**
 * Manual SMTP test cases.
 */
public class ManualSmtp {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("cfg/");
    }

    /**
     * Manual test case for Dovecot delivery testing.
     * Authentication/validation and saving to Dovecot need to be enabled.
     */
    @Test
    void inboundDovecot() throws AssertException, IOException {
        new Client()
                .send("src/test/resources/cases/config/manual/inbound.json5");
    }
}
