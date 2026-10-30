package com.mimecast.robin.smtp.extension.server;

import com.mimecast.robin.config.server.ScenarioConfig;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.extension.client.ClientMail;
import com.mimecast.robin.smtp.verb.Verb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerMailTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @Test
    void process() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        ConnectionMock connection = new ConnectionMock(stringBuilder);

        Verb verb = new Verb("MAIL FROM: <tony@example.com> SIZE=42 BODY=BINARYMIME " +
                "NOTIFY=FAILURE,DELAY ORCPT=rfc822;pepper@example.com RET=HDRS ENVID=QQ314159");

        ServerMail mail = new ServerMail();
        boolean process = mail.process(connection, verb);

        assertTrue(process);
        assertEquals("tony@example.com", mail.getAddress().getAddress());
        assertEquals("tony@example.com", connection.getSession().getEnvelopes().getLast().getMail());
        assertEquals(42, mail.getSize());
        assertEquals("BINARYMIME", mail.getBody());
        assertEquals("[FAILURE, DELAY]", Arrays.toString(mail.getNotify()));
        assertEquals("pepper@example.com", mail.getORcpt().getAddress());
        assertEquals("HDRS", mail.getRet());
        assertEquals("QQ314159", mail.getEnvId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nullSenderIsAcceptedAsRfc5321CompliantBounce(boolean skipValidation) throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        ConnectionMock connection = new ConnectionMock(stringBuilder);

        Verb verb = new Verb("MAIL FROM:<>");

        ServerMail mail = new ServerMail().setSkipFromValidation(skipValidation);
        boolean process = mail.process(connection, verb);

        assertTrue(process);
        assertTrue(connection.getOutput().contains("250 2.1.0 Sender OK"), connection.getOutput());
        assertEquals("", connection.getSession().getEnvelopes().getLast().getMail());
        assertEquals("", mail.getAddress().getAddress());
    }

    @Test
    void malformedNonBlankAddressStillRejectedByDefault() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        ConnectionMock connection = new ConnectionMock(stringBuilder);

        Verb verb = new Verb("MAIL FROM:<not-an-address>");

        ServerMail mail = new ServerMail();
        boolean process = mail.process(connection, verb);

        assertFalse(process);
        assertTrue(connection.getOutput().contains("501 5.1.3 Invalid address format"), connection.getOutput());
    }

    @Test
    void malformedNonBlankAddressAcceptedWhenSkipFromValidationEnabled() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        ConnectionMock connection = new ConnectionMock(stringBuilder);

        Verb verb = new Verb("MAIL FROM:<not-an-address>");

        ServerMail mail = new ServerMail();
        mail.setSkipFromValidation(true);
        boolean process = mail.process(connection, verb);

        assertTrue(process);
        assertTrue(connection.getOutput().contains("250 2.1.0 Sender OK"), connection.getOutput());
        assertEquals("not-an-address", connection.getSession().getEnvelopes().getLast().getMail());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MAIL", "MAIL FROM", "MAIL FROM:", "MAIL FROM:   ", "MAIL TO:<>",
            "MAIL FROM:< >", "MAIL FROM:<", "MAIL FROM:<>junk", "MAIL FROM:<bad@@example.com>"})
    void invalidSenderSyntaxIsRejectedInBothModes(String command) throws IOException {
        for (boolean skipValidation : new boolean[]{false, true}) {
            ConnectionMock connection = new ConnectionMock(new StringBuilder());
            ServerMail mail = new ServerMail().setSkipFromValidation(skipValidation);

            assertFalse(mail.process(connection, new Verb(command)), command);
            assertTrue(connection.getOutput().contains("501 5.1.3 Invalid address format"), connection.getOutput());
            assertTrue(connection.getSession().getEnvelopes().isEmpty(), command);
        }
    }

    @Test
    void acceptedNullSenderCanBeForwarded() throws IOException {
        ConnectionMock connection = new ConnectionMock(new StringBuilder("250 OK\r\n"));
        assertTrue(new ServerMail().process(connection, new Verb("MAIL FROM:<>")));

        assertTrue(new ClientMail().process(connection));
        assertTrue(connection.getOutput().contains("MAIL FROM:<>\r\n"), connection.getOutput());
    }

    @Test
    void scenarioAcceptsAndForwardsNullSender() throws IOException {
        ConnectionMock connection = new ConnectionMock(new StringBuilder("250 OK\r\n"));
        connection.setScenario(new ScenarioConfig(Map.of("mail", "250 Custom sender accepted")));

        assertTrue(new ServerMail().process(connection, new Verb("MAIL FROM:<>")));
        assertEquals("", connection.getSession().getEnvelopes().getLast().getMail());
        assertTrue(connection.getOutput().contains("250 Custom sender accepted"), connection.getOutput());
        assertTrue(new ClientMail().process(connection));
        assertTrue(connection.getOutput().contains("MAIL FROM:<>\r\n"), connection.getOutput());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validSenderIsAcceptedInBothModes(boolean skipValidation) throws IOException {
        ConnectionMock connection = new ConnectionMock(new StringBuilder());

        assertTrue(new ServerMail().setSkipFromValidation(skipValidation)
                .process(connection, new Verb("mail from: <tony@example.com> SIZE=42")));
        assertEquals("tony@example.com", connection.getSession().getEnvelopes().getLast().getMail());
    }
}
