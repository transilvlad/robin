package com.mimecast.robin.smtp.connection;

import com.mimecast.robin.config.server.ProxyRule;
import com.mimecast.robin.config.server.ScenarioConfig;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.ProxyEmailDelivery;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    private ConnectionMock getConnection(StringBuilder stringBuilder) {
        ConnectionMock connection = new ConnectionMock(stringBuilder);
        connection.getSession().setRdns("example.com");
        connection.getSession().setFriendRdns("example.net");
        connection.getSession().setFriendAddr("127.0.0.1");

        return connection;
    }

    @Test
    void streamStuffsEveryLineStartingWithADot() throws Exception {
        String body = "Subject: Dots\r\n\r\n.\r\n..\r\n.hidden\r\n...three\r\n . indented\r\nplain.\r\nbare\r.cr\r\n";
        ConnectionMock connection = getConnection(new StringBuilder());

        connection.stream(new com.mimecast.robin.smtp.io.LineInputStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));

        assertEquals("Subject: Dots\r\n\r\n..\r\n...\r\n..hidden\r\n....three\r\n . indented\r\nplain.\r\nbare\r.cr\r\n\r\n",
                connection.getOutput());
    }

    @Test
    void resetClosesOnlyTheProxyConnectionOfTheAbortedTransaction() {
        ConnectionMock connection = getConnection(new StringBuilder());
        MessageEnvelope finished = new MessageEnvelope().setMail("tony@example.com").endTransaction();
        MessageEnvelope open = new MessageEnvelope().setMail("tony@example.com");
        connection.getSession().addEnvelope(finished);
        connection.getSession().addEnvelope(open);
        ProxyEmailDelivery idle = new ProxyEmailDelivery(new Session(), finished);
        connection.getSession().setProxyConnection(new ProxyRule(Map.of("hosts", List.of("idle.example"))), idle);
        connection.getSession().setProxyConnection(new ProxyRule(Map.of("hosts", List.of("active.example"))),
                new ProxyEmailDelivery(new Session(), open));

        connection.reset();

        assertEquals(List.of(idle), List.copyOf(connection.getSession().getProxyConnections().values()));
        assertEquals(List.of(finished), connection.getSession().getEnvelopes());
    }

    @Test
    void scenarios() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("EHLO helo.com\r\n");
        stringBuilder.append("HELO example.com\r\n");
        stringBuilder.append("QUIT\r\n");

        ConnectionMock connection = getConnection(stringBuilder);
        assertTrue(connection.getScenario().isPresent());
        assertEquals("252 I think I know this user", connection.getScenario().get().getRcpt().get(0).get("response"));

        connection.setScenario(new ScenarioConfig(new HashMap<String, String>() {{
            put("ehlo", "501 Not talking to you");
        }}));
        assertTrue(connection.getScenario().isPresent());
        assertEquals("501 Not talking to you", connection.getScenario().get().getEhlo());
    }

    @Test
    void buildStreams_usesDirectSocketOutputStream_withoutBufferedWrapper() throws Exception {
        CapturingSocket socket = new CapturingSocket();
        Connection connection = new Connection(new com.mimecast.robin.smtp.session.Session());
        connection.socket = socket;

        connection.buildStreams();
        connection.out.write("250 OK\r\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(1, socket.output.writeCalls);
        assertEquals("250 OK\r\n".getBytes(StandardCharsets.UTF_8).length, socket.output.byteCount);
    }

    private static final class CapturingSocket extends Socket {
        private final TrackingOutputStream output = new TrackingOutputStream();
        private final InetSocketAddress remote = new InetSocketAddress("127.0.0.1", 2525);

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public java.net.SocketAddress getRemoteSocketAddress() {
            return remote;
        }
    }

    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        private int writeCalls;
        private int byteCount;

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            writeCalls++;
            byteCount += len;
            super.write(b, off, len);
        }
    }
}
