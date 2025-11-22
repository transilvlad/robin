package com.mimecast.robin.smtp.extension.client;

import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.connection.SmtpException;
import com.mimecast.robin.smtp.security.SecurityPolicy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * STARTTLS extension processor with DANE and MTA-STS enforcement.
 * <p>Per RFC 7672 and RFC 8461, TLS is MANDATORY when DANE or MTA-STS policies are active.
 * <br>If the server doesn't advertise STARTTLS but a security policy requires it, the connection MUST fail.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7672">RFC 7672 - DANE for SMTP</a>
 * @see <a href="https://tools.ietf.org/html/rfc8461">RFC 8461 - MTA-STS</a>
 */
public class ClientStartTls extends ClientProcessor {
    private static final Logger log = LogManager.getLogger(ClientStartTls.class);

    /**
     * STARTTLS processor with security policy enforcement.
     * <p>Enforces mandatory TLS for DANE and MTA-STS policies per RFCs.
     *
     * @param connection Connection instance.
     * @return Boolean.
     * @throws IOException Unable to communicate.
     */
    @Override
    public boolean process(Connection connection) throws IOException {
        super.process(connection);

        SecurityPolicy securityPolicy = connection.getSession().getSecurityPolicy();
        boolean tlsConfigured = connection.getSession().isTls();
        boolean tlsAdvertised = connection.getSession().isEhloTls();
        boolean tlsAlreadyActive = connection.getSession().isStartTls();

        // Check if TLS is mandatory due to security policy.
        boolean tlsMandatory = securityPolicy != null && securityPolicy.isTlsMandatory();

        if (tlsMandatory) {
            log.info("Security policy {} requires mandatory TLS for {}",
                    securityPolicy.getType(), securityPolicy.getMxHostname());

            if (!tlsAdvertised) {
                String error = String.format(
                        "Security policy %s requires TLS but server does not advertise STARTTLS (RFC %s violation)",
                        securityPolicy.getType(),
                        securityPolicy.isDane() ? "7672" : "8461"
                );
                log.error(error);
                connection.getSession().getSessionTransactionList()
                        .addTransaction("STARTTLS", "REQUIRED", error, true);
                throw new SmtpException(error);
            }
        }

        // Proceed with STARTTLS if configured and advertised.
        if ((tlsConfigured || tlsMandatory) && tlsAdvertised && !tlsAlreadyActive) {
            String write = "STARTTLS";
            connection.write(write);

            String read = connection.read("220");

            connection.getSession().getSessionTransactionList().addTransaction(write, write, read, !read.startsWith("220"));
            if (!read.startsWith("220")) {
                if (tlsMandatory) {
                    String error = String.format(
                            "Security policy %s requires TLS but STARTTLS failed: %s",
                            securityPolicy.getType(), read
                    );
                    log.error(error);
                    throw new SmtpException(error);
                }
                throw new SmtpException("STARTTLS");
            }

            connection.setProtocols(connection.getSession().getProtocols());
            connection.setCiphers(connection.getSession().getCiphers());

            try {
                connection.startTLS(true);
                String tlsInfo = connection.getProtocol() + ":" + connection.getCipherSuite();

                if (securityPolicy != null) {
                    tlsInfo += " [Policy: " + securityPolicy.getType() + "]";
                }

                connection.getSession().getSessionTransactionList().addTransaction("TLS", "", tlsInfo, false);
                log.info("TLS negotiation successful: {}", tlsInfo);
            } catch (SmtpException e) {
                String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                connection.getSession().getSessionTransactionList().addTransaction("TLS", "", message, true);

                if (tlsMandatory) {
                    log.error("TLS negotiation failed with mandatory security policy {}: {}",
                            securityPolicy.getType(), message);
                }
                throw e;
            }

            connection.getSession().setStartTls(true);
            connection.buildStreams();
            connection.getSession().setEhloLog("SHLO");
        } else if (tlsMandatory && !tlsAdvertised) {
            // This should have been caught above, but double-check.
            throw new SmtpException("TLS required by security policy but not available");
        }

        return true;
    }
}
