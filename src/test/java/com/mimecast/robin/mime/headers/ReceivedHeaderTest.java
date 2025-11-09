package com.mimecast.robin.mime.headers;

import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ReceivedHeader class.
 */
class ReceivedHeaderTest {
    
    private Session session;
    private Connection connection;
    
    @BeforeEach
    void setUp() {
        session = new Session();
        session.setEhlo("mail-relay.sender-domain.com");
        session.setFriendRdns("mail-relay.sender-domain.com");
        session.setFriendAddr("198.51.100.25");
        session.setRdns("my.server.hostname");
        session.setUID("1a2b3c4d-5678-90ef-abcd-1234567890ab");
        
        connection = new Connection(session);
    }
    
    @Test
    @DisplayName("Build basic received header without TLS")
    void buildBasicHeader() {
        ReceivedHeader header = new ReceivedHeader(connection);
        header.setRecipientAddress("user@my-company.com");
        
        String result = header.toString();
        
        assertTrue(result.startsWith("Received: from mail-relay.sender-domain.com"), 
                "Should start with 'Received: from' and EHLO domain");
        assertTrue(result.contains("(mail-relay.sender-domain.com [198.51.100.25])"), 
                "Should contain hostname and IP address in parentheses");
        assertTrue(result.contains("by my.server.hostname (RobinMTA)"), 
                "Should contain 'by' clause with hostname");
        assertTrue(result.contains("with ESMTP"), 
                "Should contain protocol");
        assertTrue(result.contains("id 1a2b3c4d-5678-90ef-abcd-1234567890ab"), 
                "Should contain message ID");
        assertTrue(result.contains("for <user@my-company.com>"), 
                "Should contain recipient address");
        assertTrue(result.contains(session.getDate()), 
                "Should contain date/time");
    }
    
    @Test
    @DisplayName("Build received header with TLS")
    void buildHeaderWithTls() {
        session.setStartTls(true);
        
        // Create a test connection that overrides getTLS methods
        Connection tlsConnection = new TestConnectionWithTls(session);
        
        ReceivedHeader header = new ReceivedHeader(tlsConnection);
        header.setRecipientAddress("user@my-company.com");
        
        String result = header.toString();
        
        assertTrue(result.contains("with ESMTPS"), 
                "Should use ESMTPS for TLS connections");
        assertTrue(result.contains("version=TLSv1.3"), 
                "Should contain TLS version");
        assertTrue(result.contains("cipher=TLS_AES_256_GCM_SHA384"), 
                "Should contain cipher suite");
        assertTrue(result.contains("bits=256/256"), 
                "Should contain bit strength");
    }
    
    @Test
    @DisplayName("Build received header with HELO instead of EHLO")
    void buildHeaderWithHelo() {
        session.setEhlo("");
        session.setHelo("mail-relay.sender-domain.com");
        
        ReceivedHeader header = new ReceivedHeader(connection);
        
        String result = header.toString();
        
        assertTrue(result.contains("from mail-relay.sender-domain.com"), 
                "Should use HELO domain");
        assertTrue(result.contains("with SMTP"), 
                "Should use SMTP protocol for HELO");
    }
    
    @Test
    @DisplayName("Build received header without recipient address")
    void buildHeaderWithoutRecipient() {
        ReceivedHeader header = new ReceivedHeader(connection);
        
        String result = header.toString();
        
        assertFalse(result.contains("for <"), 
                "Should not contain 'for' clause without recipient");
        assertTrue(result.contains(";"), 
                "Should still contain semicolon");
    }
    
    @Test
    @DisplayName("Build received header with custom values via setters")
    void buildHeaderWithCustomValues() {
        ReceivedHeader header = new ReceivedHeader(connection);
        header.setFromDomain("custom.sender.com");
        header.setFromHostname("custom-hostname");
        header.setFromIpAddress("192.0.2.1");
        header.setByHostname("custom.receiver.com");
        header.setProtocol("ESMTPA");
        header.setMessageId("custom-message-id");
        header.setRecipientAddress("custom@example.com");
        
        String result = header.toString();
        
        assertTrue(result.contains("from custom.sender.com"), 
                "Should use custom from domain");
        assertTrue(result.contains("custom-hostname [192.0.2.1]"), 
                "Should use custom hostname and IP");
        assertTrue(result.contains("by custom.receiver.com"), 
                "Should use custom by hostname");
        assertTrue(result.contains("with ESMTPA"), 
                "Should use custom protocol");
        assertTrue(result.contains("id custom-message-id"), 
                "Should use custom message ID");
        assertTrue(result.contains("for <custom@example.com>"), 
                "Should use custom recipient");
    }
    
    @Test
    @DisplayName("Build received header with minimal information")
    void buildHeaderWithMinimalInfo() {
        Session minimalSession = new Session();
        Connection minimalConnection = new Connection(minimalSession);
        
        ReceivedHeader header = new ReceivedHeader(minimalConnection);
        
        String result = header.toString();
        
        assertTrue(result.startsWith("Received: "), 
                "Should start with 'Received: '");
        assertTrue(result.contains(minimalSession.getDate()), 
                "Should at least contain date/time");
    }
    
    @Test
    @DisplayName("Build received header with only hostname, no IP")
    void buildHeaderWithOnlyHostname() {
        session.setFriendAddr("");
        
        ReceivedHeader header = new ReceivedHeader(connection);
        
        String result = header.toString();
        
        assertTrue(result.contains("(mail-relay.sender-domain.com)"), 
                "Should contain hostname in parentheses");
        assertFalse(result.contains("["), 
                "Should not contain IP brackets when IP is missing");
    }
    
    @Test
    @DisplayName("Build received header with only IP, no hostname")
    void buildHeaderWithOnlyIp() {
        session.setFriendRdns("");
        
        ReceivedHeader header = new ReceivedHeader(connection);
        
        String result = header.toString();
        
        assertTrue(result.contains("[198.51.100.25]"), 
                "Should contain IP in brackets");
    }
    
    @Test
    @DisplayName("Header should use CRLF line endings")
    void headerShouldUseCrLf() {
        ReceivedHeader header = new ReceivedHeader(connection);
        
        String result = header.toString();
        
        assertTrue(result.contains("\r\n"), 
                "Should use CRLF line endings");
        assertFalse(result.matches(".*[^\r]\n.*"), 
                "Should not use LF without CR");
    }
    
    @Test
    @DisplayName("Extract 128-bit cipher strength")
    void extract128BitCipherStrength() {
        session.setStartTls(true);
        
        Connection tlsConnection = new TestConnectionWithTls(session, "TLSv1.2", "TLS_RSA_WITH_AES_128_CBC_SHA");
        
        ReceivedHeader header = new ReceivedHeader(tlsConnection);
        
        String result = header.toString();
        
        assertTrue(result.contains("bits=128/128"), 
                "Should extract 128-bit strength from cipher name");
    }
    
    @Test
    @DisplayName("Extract 192-bit cipher strength")
    void extract192BitCipherStrength() {
        session.setStartTls(true);
        
        Connection tlsConnection = new TestConnectionWithTls(session, "TLSv1.2", "TLS_RSA_WITH_AES_192_CBC_SHA");
        
        ReceivedHeader header = new ReceivedHeader(tlsConnection);
        
        String result = header.toString();
        
        assertTrue(result.contains("bits=192/192"), 
                "Should extract 192-bit strength from cipher name");
    }
    
    @Test
    @DisplayName("Handle cipher without recognizable bit strength")
    void handleCipherWithoutRecognizableBits() {
        session.setStartTls(true);
        
        Connection tlsConnection = new TestConnectionWithTls(session, "TLSv1.2", "TLS_UNKNOWN_CIPHER");
        
        ReceivedHeader header = new ReceivedHeader(tlsConnection);
        
        String result = header.toString();
        
        assertFalse(result.contains("bits="), 
                "Should not include bits when strength cannot be determined");
        assertTrue(result.contains("cipher=TLS_UNKNOWN_CIPHER"), 
                "Should still include cipher suite name");
    }
    
    @Test
    @DisplayName("Build header with null connection")
    void buildHeaderWithNullConnection() {
        ReceivedHeader header = new ReceivedHeader(null);
        
        String result = header.toString();
        
        assertNotNull(result, "Should return a string even with null connection");
        assertTrue(result.startsWith("Received: "), 
                "Should start with 'Received: '");
    }
    
    @Test
    @DisplayName("Verify header inherits from MimeHeader")
    void verifyHeaderInheritance() {
        ReceivedHeader header = new ReceivedHeader(connection);
        
        assertTrue(header instanceof MimeHeader, 
                "ReceivedHeader should extend MimeHeader");
        assertEquals("Received", header.getName(), 
                "Header name should be 'Received'");
    }
    
    @Test
    @DisplayName("Build header with recipient address containing special characters")
    void buildHeaderWithSpecialCharactersInRecipient() {
        ReceivedHeader header = new ReceivedHeader(connection);
        header.setRecipientAddress("user+test@my-company.com");
        
        String result = header.toString();
        
        assertTrue(result.contains("for <user+test@my-company.com>"), 
                "Should properly handle recipient with special characters");
    }
    
    /**
     * Test helper class that extends Connection to provide test TLS details.
     */
    private static class TestConnectionWithTls extends Connection {
        private final String protocol;
        private final String cipherSuite;
        
        public TestConnectionWithTls(Session session) {
            this(session, "TLSv1.3", "TLS_AES_256_GCM_SHA384");
        }
        
        public TestConnectionWithTls(Session session, String protocol, String cipherSuite) {
            super(session);
            this.protocol = protocol;
            this.cipherSuite = cipherSuite;
        }
        
        @Override
        public String getProtocol() {
            return protocol;
        }
        
        @Override
        public String getCipherSuite() {
            return cipherSuite;
        }
    }
}
