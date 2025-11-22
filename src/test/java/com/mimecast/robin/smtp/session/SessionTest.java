package com.mimecast.robin.smtp.session;

import com.mimecast.robin.config.server.ProxyRule;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.ProxyEmailDelivery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Session tests for proxy connection management.
 */
class SessionTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    /**
     * Creates a test proxy rule.
     *
     * @param host Host name.
     * @param port Port number.
     * @return ProxyRule instance.
     */
    private ProxyRule createProxyRule(String host, int port) {
        Map<String, Object> ruleMap = new HashMap<>();
        ruleMap.put("hosts", List.of(host));
        ruleMap.put("port", port);
        ruleMap.put("protocol", "esmtp");
        return new ProxyRule(ruleMap);
    }

    /**
     * Creates a test proxy session.
     *
     * @return Session instance.
     */
    private Session createProxySession(String host, int port) {
        Session session = new Session();
        session.setDirection(EmailDirection.OUTBOUND);
        session.setMx(Collections.singletonList(host));
        session.setPort(port);
        session.setEhlo("test.example.com");
        return session;
    }

    @Test
    void testGetProxyConnectionNull() {
        Session session = new Session();
        ProxyRule rule = createProxyRule("localhost", 25);

        // Should return null when no connection exists.
        assertNull(session.getProxyConnection(rule));
    }

    @Test
    void testSetAndGetProxyConnection() {
        Session session = new Session();
        ProxyRule rule = createProxyRule("localhost", 25);

        Session proxySession = createProxySession("localhost", 25);
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("sender@example.com");

        ProxyEmailDelivery delivery = new ProxyEmailDelivery(proxySession, envelope);

        // Set the connection.
        session.setProxyConnection(rule, delivery);

        // Should return the same connection.
        ProxyEmailDelivery retrieved = session.getProxyConnection(rule);
        assertNotNull(retrieved);
        assertEquals(delivery, retrieved);
    }

    @Test
    void testMultipleProxyConnections() {
        Session session = new Session();
        ProxyRule rule1 = createProxyRule("relay1.example.com", 25);
        ProxyRule rule2 = createProxyRule("relay2.example.com", 25);

        Session proxySession1 = createProxySession("relay1.example.com", 25);
        Session proxySession2 = createProxySession("relay2.example.com", 25);

        MessageEnvelope envelope1 = new MessageEnvelope();
        envelope1.setMail("sender1@example.com");
        MessageEnvelope envelope2 = new MessageEnvelope();
        envelope2.setMail("sender2@example.com");

        ProxyEmailDelivery delivery1 = new ProxyEmailDelivery(proxySession1, envelope1);
        ProxyEmailDelivery delivery2 = new ProxyEmailDelivery(proxySession2, envelope2);

        // Set both connections.
        session.setProxyConnection(rule1, delivery1);
        session.setProxyConnection(rule2, delivery2);

        // Should retrieve correct connections.
        assertEquals(delivery1, session.getProxyConnection(rule1));
        assertEquals(delivery2, session.getProxyConnection(rule2));

        // Should have 2 connections.
        assertEquals(2, session.getProxyConnections().size());
    }

    @Test
    void testOverwriteProxyConnection() {
        Session session = new Session();
        ProxyRule rule = createProxyRule("localhost", 25);

        Session proxySession1 = createProxySession("localhost", 25);
        Session proxySession2 = createProxySession("localhost", 25);

        MessageEnvelope envelope1 = new MessageEnvelope();
        envelope1.setMail("sender1@example.com");
        MessageEnvelope envelope2 = new MessageEnvelope();
        envelope2.setMail("sender2@example.com");

        ProxyEmailDelivery delivery1 = new ProxyEmailDelivery(proxySession1, envelope1);
        ProxyEmailDelivery delivery2 = new ProxyEmailDelivery(proxySession2, envelope2);

        // Set first connection.
        session.setProxyConnection(rule, delivery1);
        assertEquals(delivery1, session.getProxyConnection(rule));

        // Overwrite with second connection.
        session.setProxyConnection(rule, delivery2);
        assertEquals(delivery2, session.getProxyConnection(rule));

        // Should have only 1 connection.
        assertEquals(1, session.getProxyConnections().size());
    }

    @Test
    void testGetProxyConnections() {
        Session session = new Session();

        // Should be empty initially.
        assertTrue(session.getProxyConnections().isEmpty());

        ProxyRule rule1 = createProxyRule("relay1.example.com", 25);
        ProxyRule rule2 = createProxyRule("relay2.example.com", 587);

        Session proxySession1 = createProxySession("relay1.example.com", 25);
        Session proxySession2 = createProxySession("relay2.example.com", 587);

        MessageEnvelope envelope1 = new MessageEnvelope();
        envelope1.setMail("sender1@example.com");
        MessageEnvelope envelope2 = new MessageEnvelope();
        envelope2.setMail("sender2@example.com");

        ProxyEmailDelivery delivery1 = new ProxyEmailDelivery(proxySession1, envelope1);
        ProxyEmailDelivery delivery2 = new ProxyEmailDelivery(proxySession2, envelope2);

        session.setProxyConnection(rule1, delivery1);
        session.setProxyConnection(rule2, delivery2);

        Map<ProxyRule, ProxyEmailDelivery> connections = session.getProxyConnections();
        assertEquals(2, connections.size());
        assertTrue(connections.containsKey(rule1));
        assertTrue(connections.containsKey(rule2));
        assertEquals(delivery1, connections.get(rule1));
        assertEquals(delivery2, connections.get(rule2));
    }

    @Test
    void testCloseProxyConnections() {
        Session session = new Session();
        ProxyRule rule1 = createProxyRule("relay1.example.com", 25);
        ProxyRule rule2 = createProxyRule("relay2.example.com", 587);

        Session proxySession1 = createProxySession("relay1.example.com", 25);
        Session proxySession2 = createProxySession("relay2.example.com", 587);

        MessageEnvelope envelope1 = new MessageEnvelope();
        envelope1.setMail("sender1@example.com");
        MessageEnvelope envelope2 = new MessageEnvelope();
        envelope2.setMail("sender2@example.com");

        ProxyEmailDelivery delivery1 = new ProxyEmailDelivery(proxySession1, envelope1);
        ProxyEmailDelivery delivery2 = new ProxyEmailDelivery(proxySession2, envelope2);

        session.setProxyConnection(rule1, delivery1);
        session.setProxyConnection(rule2, delivery2);

        assertEquals(2, session.getProxyConnections().size());

        // Close all connections.
        session.closeProxyConnections();

        // Connections map should be empty.
        assertTrue(session.getProxyConnections().isEmpty());

        // Connections should be closed (not connected).
        assertFalse(delivery1.isConnected());
        assertFalse(delivery2.isConnected());
    }

    @Test
    void testProxyConnectionsNotSerializedButStillAccessible() {
        Session session = new Session();
        ProxyRule rule = createProxyRule("localhost", 25);

        Session proxySession = createProxySession("localhost", 25);
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("sender@example.com");

        ProxyEmailDelivery delivery = new ProxyEmailDelivery(proxySession, envelope);

        session.setProxyConnection(rule, delivery);

        // Proxy connections map is transient, so it won't be serialized.
        // But it should still be accessible in memory.
        assertNotNull(session.getProxyConnections());
        assertEquals(1, session.getProxyConnections().size());
    }

    @Test
    void testConnectionReuseAcrossEnvelopes() {
        Session session = new Session();
        ProxyRule rule = createProxyRule("relay.example.com", 25);

        Session proxySession = createProxySession("relay.example.com", 25);
        MessageEnvelope envelope1 = new MessageEnvelope();
        envelope1.setMail("sender1@example.com");

        ProxyEmailDelivery delivery = new ProxyEmailDelivery(proxySession, envelope1);

        // Store connection for first envelope.
        session.setProxyConnection(rule, delivery);

        // Simulate second envelope reusing same connection.
        ProxyEmailDelivery retrieved = session.getProxyConnection(rule);
        assertNotNull(retrieved);
        assertEquals(delivery, retrieved);

        // Prepare for second envelope.
        MessageEnvelope envelope2 = new MessageEnvelope();
        envelope2.setMail("sender2@example.com");
        retrieved.prepareForEnvelope(envelope2);

        // Connection should now be for envelope2.
        assertTrue(retrieved.isForCurrentEnvelope(envelope2));
        assertFalse(retrieved.isForCurrentEnvelope(envelope1));
    }
}
