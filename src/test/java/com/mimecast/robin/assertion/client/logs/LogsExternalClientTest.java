package com.mimecast.robin.assertion.client.logs;

import com.mimecast.robin.assertion.AssertException;
import com.mimecast.robin.config.assertion.AssertConfig;
import com.mimecast.robin.config.assertion.external.logs.LogsExternalClientConfig;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogsExternalClientTest {

    @Test
    void uid() throws AssertException {
        Map<String, Object> logsExternalClientConfig = Stream.of(
                new AbstractMap.SimpleEntry<>("type", "logs"),
                new AbstractMap.SimpleEntry<>("wait", 0),
                new AbstractMap.SimpleEntry<>("delay", 0),
                new AbstractMap.SimpleEntry<>("retry", 0),
                new AbstractMap.SimpleEntry<>("verify", Collections.singletonList("QUIT")),
                new AbstractMap.SimpleEntry<>("match", Arrays.asList(
                        Arrays.asList("MAIL FROM", "<tony@example.com>", "SIZE=294"),
                        Arrays.asList("RCPT TO", "<pepper@example.com>")
                )),
                new AbstractMap.SimpleEntry<>("refuse", Collections.singletonList(
                        Collections.singletonList("java.lang.NullPointerException")
                ))
        ).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("220 example.com ESMTP\r\n");
        stringBuilder.append("250-smtp.example.com at your service, [rescueMe.local]\r\n" +
                "250 HELP\r\n");
        stringBuilder.append("250 2.1.0 Sender OK\r\n");
        stringBuilder.append("250 2.1.5 Recipient OK\r\n");
        stringBuilder.append("354 Ready and willing\r\n");
        stringBuilder.append("250 2.0.0 Received OK\r\n");
        stringBuilder.append("221 2.0.0 Closing connection\r\n");

        EnvelopeTransactionList envelopeTransactionList = new EnvelopeTransactionList();
        envelopeTransactionList.addTransaction("MAIL", "250 ok [rescueMe]");

        Connection connection = ConnectionMock.getConnection(stringBuilder)
                .setServer("space.com");
        connection.getSession().getSessionTransactionList().getEnvelopes().add(envelopeTransactionList);

        new LogsExternalClientMock()
                .setConnection(connection)
                .setConfig(new LogsExternalClientConfig(logsExternalClientConfig))
                .setTransactionId(0)
                .run();
    }

    private static LogsExternalClientMock client(Map<String, Object> config) {
        Map<String, Object> map = new HashMap<>(Map.of("type", "logs", "wait", 0, "delay", 0));
        map.putAll(config);

        EnvelopeTransactionList envelopeTransactionList = new EnvelopeTransactionList();
        envelopeTransactionList.addTransaction("MAIL", "250 ok [rescueMe]");

        Connection connection = new ConnectionMock();
        connection.getSession().addAssertions(new AssertConfig(new HashMap<>(Map.of("verifyFails", true))));
        connection.getSession().getSessionTransactionList().getEnvelopes().add(envelopeTransactionList);

        LogsExternalClientMock client = new LogsExternalClientMock();
        client.setConnection(connection);
        client.setConfig(new LogsExternalClientConfig(map));
        client.setTransactionId(0);
        return client;
    }

    @Test
    void verifyMissFails() {
        AssertException e = assertThrows(AssertException.class,
                () -> client(Map.of("retry", 2, "verify", List.of("Dovecot-LDA delivery successful"))).run());
        assertTrue(e.getMessage().contains("verify"));
    }

    @Test
    void noLogsFails() {
        assertThrows(AssertException.class,
                () -> client(Map.of("grep", List.of(Map.of("pattern", "not in the log")))).run());
    }

    @Test
    void verifyNoneFailsWhenLinesFound() {
        AssertException e = assertThrows(AssertException.class,
                () -> client(Map.of("verifyNone", true, "grep", List.of(Map.of("pattern", "RCPT TO")))).run());
        assertTrue(e.getMessage().contains("none expected"));
    }

    @Test
    void verifyNonePassesWithoutLines() throws AssertException {
        client(Map.of("verifyNone", true, "retry", 2, "grep", List.of(Map.of("pattern", "not in the log")))).run();
    }

    @Test
    void grepInvertExcludesLines() throws AssertException {
        client(Map.of(
                "grep", List.of(
                        Map.of("pattern", "rescueMe"),
                        Map.of("parameter", "-v", "pattern", "DEBUG")),
                "verify", List.of("QUIT"),
                "refuse", List.of(List.of("Closing Transmission Channel"))
        )).run();
    }

    @Test
    void grepCountReturnsMatchingLines() throws AssertException {
        client(Map.of(
                "grep", List.of(Map.of("parameter", "-cE", "pattern", "> (MAIL|RCPT)")),
                "verify", List.of("^2$")
        )).run();
    }

    @Test
    void refuseFailsWhenMatched() {
        assertThrows(AssertException.class, () -> client(Map.of(
                "verify", List.of("QUIT"),
                "refuse", List.of(List.of("Accepted connection", "8\\.8\\.8\\.8"))
        )).run());
    }
}
