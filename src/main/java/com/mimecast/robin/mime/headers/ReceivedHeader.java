package com.mimecast.robin.mime.headers;

import com.mimecast.robin.smtp.connection.Connection;
import org.apache.commons.lang3.StringUtils;

import javax.net.ssl.SSLSocket;

/**
 * Received header builder.
 * 
 * <p>Builds a standard email received header using the Connection instance to collect
 * socket and session information. The header follows RFC 5321 and RFC 5322 format.
 * 
 * <p>Example output:
 * <pre>
 * Received: from mail-relay.sender-domain.com (mail-relay.sender-domain.com [198.51.100.25])
 *     by my.server.hostname (RobinMTA)
 *     with ESMTPS (version=TLS1_3 cipher=TLS_AES_256_GCM_SHA384 bits=256/256)
 *     id 1a2b3c4d-5678-90ef-abcd-1234567890ab
 *     for &lt;user@my-company.com&gt;;
 *     Tue, 8 Nov 2025 20:33:10 +0000
 * </pre>
 * 
 * @see Connection
 * @see <a href="https://tools.ietf.org/html/rfc5321">RFC 5321</a>
 * @see <a href="https://tools.ietf.org/html/rfc5322">RFC 5322</a>
 */
public class ReceivedHeader extends MimeHeader {
    
    private final Connection connection;
    private String fromDomain;
    private String fromHostname;
    private String fromIpAddress;
    private String byHostname;
    private String protocol;
    private String messageId;
    private String recipientAddress;
    
    /**
     * Constructs a new ReceivedHeader instance with given Connection.
     * 
     * @param connection Connection instance containing socket and session information.
     */
    public ReceivedHeader(Connection connection) {
        super("Received", "");
        this.connection = connection;
        this.protocol = "ESMTP";
        extractConnectionInfo();
    }
    
    /**
     * Extracts information from the connection instance.
     */
    private void extractConnectionInfo() {
        if (connection != null && connection.getSession() != null) {
            // Extract EHLO/HELO name
            if (StringUtils.isNotBlank(connection.getSession().getEhlo())) {
                this.fromDomain = connection.getSession().getEhlo();
            } else if (StringUtils.isNotBlank(connection.getSession().getHelo())) {
                this.fromDomain = connection.getSession().getHelo();
            }
            
            // Extract remote hostname and IP address
            this.fromHostname = connection.getSession().getFriendRdns();
            this.fromIpAddress = connection.getSession().getFriendAddr();
            
            // Extract local hostname
            this.byHostname = connection.getSession().getRdns();
            
            // Extract message ID
            this.messageId = connection.getSession().getUID();
            
            // Determine protocol based on TLS status
            if (connection.getSession().isStartTls()) {
                this.protocol = "ESMTPS";
            } else if (StringUtils.isNotBlank(connection.getSession().getEhlo())) {
                this.protocol = "ESMTP";
            } else if (StringUtils.isNotBlank(connection.getSession().getHelo())) {
                this.protocol = "SMTP";
            }
        }
    }
    
    /**
     * Sets the "from" domain (HELO/EHLO name).
     * 
     * @param fromDomain HELO/EHLO domain name.
     * @return Self.
     */
    public ReceivedHeader setFromDomain(String fromDomain) {
        this.fromDomain = fromDomain;
        return this;
    }
    
    /**
     * Sets the "from" hostname (reverse DNS).
     * 
     * @param fromHostname Public hostname.
     * @return Self.
     */
    public ReceivedHeader setFromHostname(String fromHostname) {
        this.fromHostname = fromHostname;
        return this;
    }
    
    /**
     * Sets the "from" IP address.
     * 
     * @param fromIpAddress IP address.
     * @return Self.
     */
    public ReceivedHeader setFromIpAddress(String fromIpAddress) {
        this.fromIpAddress = fromIpAddress;
        return this;
    }
    
    /**
     * Sets the "by" hostname (server's hostname).
     * 
     * @param byHostname Server hostname.
     * @return Self.
     */
    public ReceivedHeader setByHostname(String byHostname) {
        this.byHostname = byHostname;
        return this;
    }
    
    /**
     * Sets the protocol (SMTP, ESMTP, ESMTPS, etc.).
     * 
     * @param protocol Protocol string.
     * @return Self.
     */
    public ReceivedHeader setProtocol(String protocol) {
        this.protocol = protocol;
        return this;
    }
    
    /**
     * Sets the message ID.
     * 
     * @param messageId Local message ID.
     * @return Self.
     */
    public ReceivedHeader setMessageId(String messageId) {
        this.messageId = messageId;
        return this;
    }
    
    /**
     * Sets the recipient address (envelope recipient).
     * 
     * @param recipientAddress Envelope recipient address.
     * @return Self.
     */
    public ReceivedHeader setRecipientAddress(String recipientAddress) {
        this.recipientAddress = recipientAddress;
        return this;
    }
    
    /**
     * Builds the TLS details string.
     * 
     * @return TLS details string or empty if no TLS.
     */
    private String buildTlsDetails() {
        if (connection == null) {
            return "";
        }
        
        String tlsProtocol = connection.getProtocol();
        String cipherSuite = connection.getCipherSuite();
        
        if (StringUtils.isBlank(tlsProtocol) || StringUtils.isBlank(cipherSuite)) {
            return "";
        }
        
        StringBuilder tlsDetails = new StringBuilder();
        tlsDetails.append(" (");
        
        // Add protocol version
        tlsDetails.append("version=").append(tlsProtocol);
        
        // Add cipher suite
        tlsDetails.append(" cipher=").append(cipherSuite);
        
        // Try to extract bits from cipher suite name
        String bits = extractBitsFromCipher(cipherSuite);
        if (StringUtils.isNotBlank(bits)) {
            tlsDetails.append(" bits=").append(bits);
        }
        
        tlsDetails.append(")");
        return tlsDetails.toString();
    }
    
    /**
     * Extracts bit strength from cipher suite name.
     * 
     * @param cipherSuite Cipher suite name.
     * @return Bits string (e.g., "256/256") or empty string.
     */
    private String extractBitsFromCipher(String cipherSuite) {
        if (cipherSuite == null) {
            return "";
        }
        
        // Common patterns in cipher suite names
        if (cipherSuite.contains("256")) {
            return "256/256";
        } else if (cipherSuite.contains("128")) {
            return "128/128";
        } else if (cipherSuite.contains("192")) {
            return "192/192";
        }
        
        return "";
    }
    
    /**
     * Returns a string representation of the received header.
     * 
     * <p>The header is formatted according to RFC 5321/5322 with multiple lines
     * for readability. Each component is on its own line with proper indentation.
     * 
     * @return Formatted received header string with CRLF line endings.
     */
    @Override
    public String toString() {
        StringBuilder header = new StringBuilder();
        header.append(name).append(": ");
        
        // Build "from" clause
        if (StringUtils.isNotBlank(fromDomain)) {
            header.append("from ").append(fromDomain);
            
            // Add hostname and IP in parentheses
            if (StringUtils.isNotBlank(fromHostname) || StringUtils.isNotBlank(fromIpAddress)) {
                header.append(" (");
                if (StringUtils.isNotBlank(fromHostname)) {
                    header.append(fromHostname);
                }
                if (StringUtils.isNotBlank(fromIpAddress)) {
                    if (StringUtils.isNotBlank(fromHostname)) {
                        header.append(" ");
                    }
                    header.append("[").append(fromIpAddress).append("]");
                }
                header.append(")");
            }
            header.append("\r\n");
        }
        
        // Build "by" clause
        if (StringUtils.isNotBlank(byHostname)) {
            header.append("    by ").append(byHostname).append(" (RobinMTA)");
            header.append("\r\n");
        }
        
        // Build "with" clause (protocol and TLS details)
        if (StringUtils.isNotBlank(protocol)) {
            header.append("    with ").append(protocol);
            String tlsDetails = buildTlsDetails();
            if (StringUtils.isNotBlank(tlsDetails)) {
                header.append(tlsDetails);
            }
            header.append("\r\n");
        }
        
        // Build "id" clause
        if (StringUtils.isNotBlank(messageId)) {
            header.append("    id ").append(messageId);
            header.append("\r\n");
        }
        
        // Build "for" clause
        if (StringUtils.isNotBlank(recipientAddress)) {
            header.append("    for <").append(recipientAddress).append(">");
            header.append(";\r\n");
        } else if (header.length() > 0) {
            // Add semicolon even without recipient
            header.append("    ;\r\n");
        }
        
        // Add date/time
        if (connection != null && connection.getSession() != null) {
            String date = connection.getSession().getDate();
            if (StringUtils.isNotBlank(date)) {
                header.append("    ").append(date);
                header.append("\r\n");
            }
        }
        
        return header.toString();
    }
}
