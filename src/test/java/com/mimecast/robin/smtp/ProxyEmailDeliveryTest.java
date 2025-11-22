package com.mimecast.robin.smtp;

import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProxyEmailDelivery tests.
 * <p>Tests the connection reuse feature for proxy forwarding.
 */
class ProxyEmailDeliveryTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    /**
     * Creates a test session configured for proxy.
     *
     * @return Session instance.
     */
    private Session createProxySession() {
        Session session = new Session();
        session.setDirection(EmailDirection.OUTBOUND);
        session.setMx(Collections.singletonList("localhost"));
        session.setPort(25);
        session.setEhlo("test.example.com");
        return session;
    }

    /**
     * Creates a test envelope.
     *
     * @return MessageEnvelope instance.
     */
    private MessageEnvelope createEnvelope() {
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("sender@example.com");
        envelope.addRcpt("recipient@example.com");
        return envelope;
    }

    @Test
    void testConstructor() {
        Session session = createProxySession();
        MessageEnvelope envelope = createEnvelope();

        ProxyEmailDelivery delivery = new ProxyEmailDelivery(session, envelope);

        assertNotNull(delivery);
        assertFalse(delivery.isConnected());
        assertTrue(delivery.isForCurrentEnvelope(envelope));
    }

    @Test
    void testIsForCurrentEnvelope() {
        Session session = createProxySession();
        MessageEnvelope envelope1 = createEnvelope();
        MessageEnvelope envelope2 = createEnvelope();

        ProxyEmailDelivery delivery = new ProxyEmailDelivery(session, envelope1);

        assertTrue(delivery.isForCurrentEnvelope(envelope1));
        assertFalse(delivery.isForCurrentEnvelope(envelope2));
    }

    @Test
    void testPrepareForEnvelope() {
        Session session = createProxySession();
        MessageEnvelope envelope1 = createEnvelope();
        MessageEnvelope envelope2 = createEnvelope();

        ProxyEmailDelivery delivery = new ProxyEmailDelivery(session, envelope1);
        assertTrue(delivery.isForCurrentEnvelope(envelope1));

        // Prepare for new envelope.
        delivery.prepareForEnvelope(envelope2);
        assertTrue(delivery.isForCurrentEnvelope(envelope2));
        assertFalse(delivery.isForCurrentEnvelope(envelope1));
    }

    @Test
    void testPrepareForSameEnvelope() {
        Session session = createProxySession();
        MessageEnvelope envelope = createEnvelope();

        ProxyEmailDelivery delivery = new ProxyEmailDelivery(session, envelope);
        assertTrue(delivery.isForCurrentEnvelope(envelope));

        // Prepare for same envelope should be no-op.
        delivery.prepareForEnvelope(envelope);
        assertTrue(delivery.isForCurrentEnvelope(envelope));
    }

    @Test
    void testSendRcptBeforeConnect() {
        Session session = createProxySession();
        MessageEnvelope envelope = createEnvelope();

        ProxyEmailDelivery delivery = new ProxyEmailDelivery(session, envelope);

        // Should throw IOException when not connected.
        IOException exception = assertThrows(IOException.class, () -> {
            delivery.sendRcpt("test@example.com");
        });
        assertEquals("Cannot send RCPT: not connected", exception.getMessage());
    }

    @Test
    void testSendDataBeforeConnect() {
        Session session = createProxySession();
        MessageEnvelope envelope = createEnvelope();

        ProxyEmailDelivery delivery = new ProxyEmailDelivery(session, envelope);

        // Should throw IOException when not connected.
        IOException exception = assertThrows(IOException.class, () -> {
            delivery.sendData();
        });
        assertEquals("Cannot send DATA: not connected", exception.getMessage());
    }

    @Test
    void testCloseBeforeConnect() {
        Session session = createProxySession();
        MessageEnvelope envelope = createEnvelope();

        ProxyEmailDelivery delivery = new ProxyEmailDelivery(session, envelope);

        // Should not throw when closing before connect.
        assertDoesNotThrow(() -> delivery.close());
        assertFalse(delivery.isConnected());
    }

    @Test
    void testConnectionReuse() {
        Session session = createProxySession();
        MessageEnvelope envelope1 = createEnvelope();
        MessageEnvelope envelope2 = createEnvelope();

        ProxyEmailDelivery delivery = new ProxyEmailDelivery(session, envelope1);

        // After preparation for new envelope, should still be for envelope1 until explicitly changed.
        assertTrue(delivery.isForCurrentEnvelope(envelope1));

        // Prepare for envelope2.
        delivery.prepareForEnvelope(envelope2);
        assertTrue(delivery.isForCurrentEnvelope(envelope2));
        assertFalse(delivery.isForCurrentEnvelope(envelope1));
    }

    @Test
    void testMultipleEnvelopePreparation() {
        Session session = createProxySession();
        MessageEnvelope envelope1 = createEnvelope();
        MessageEnvelope envelope2 = createEnvelope();
        MessageEnvelope envelope3 = createEnvelope();

        ProxyEmailDelivery delivery = new ProxyEmailDelivery(session, envelope1);
        assertTrue(delivery.isForCurrentEnvelope(envelope1));

        delivery.prepareForEnvelope(envelope2);
        assertTrue(delivery.isForCurrentEnvelope(envelope2));

        delivery.prepareForEnvelope(envelope3);
        assertTrue(delivery.isForCurrentEnvelope(envelope3));
    }
}
