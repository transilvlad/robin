package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.db.SharedDataSource;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.Connection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Explicit account-to-address grants; aliases never imply permission to send.
 */
public final class SenderAuthorization {
    private static final Logger log = LogManager.getLogger(SenderAuthorization.class);
    public static final String DEFAULT_QUERY = """
            SELECT g.address FROM mail_sender_grants g
            JOIN users u ON lower(u.email)=g.account
            JOIN mail_domains d ON d.domain=split_part(g.address,'@',2)
            WHERE g.account=? AND g.address=? AND d.enabled=true
            """;

    private final DataSource dataSource;
    private final String query;

    public SenderAuthorization(DataSource dataSource, String query) {
        this.dataSource = dataSource;
        this.query = query;
    }

    public static boolean isEnabled() {
        return configuration().getBooleanProperty("enabled", false);
    }

    private static BasicConfig configuration() {
        return new BasicConfig(Config.getServer().getMapProperty("senderAuthorization"));
    }

    private static SenderAuthorization configured() {
        return new SenderAuthorization(SharedDataSource.getDataSource(),
                configuration().getStringProperty("query", DEFAULT_QUERY));
    }

    public boolean isAuthorized(String account, String address) throws SQLException {
        if (account == null || account.isBlank() || address == null || address.isBlank()) {
            return false;
        }
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(query)) {
            statement.setQueryTimeout(5);
            statement.setString(1, account.trim().toLowerCase(Locale.ROOT));
            statement.setString(2, address.trim().toLowerCase(Locale.ROOT));
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public boolean isHeaderAuthorized(String account, EmailParser parser) throws SQLException, AddressException {
        if (parser == null || !parser.hasValidHeaders() || parser.getHeaders().getAll("From").size() != 1) {
            return false;
        }
        InternetAddress[] authors = InternetAddress.parseHeader(
                parser.getHeaders().getAll("From").getFirst().getValue(), true);
        if (authors.length != 1 || authors[0].isGroup()) {
            return false;
        }
        authors[0].validate();
        if (!isAuthorized(account, authors[0].getAddress())) {
            return false;
        }
        var senders = parser.getHeaders().getAll("Sender");
        if (senders.isEmpty()) {
            return true;
        }
        if (senders.size() != 1) {
            return false;
        }
        InternetAddress[] sender = InternetAddress.parseHeader(senders.getFirst().getValue(), true);
        if (sender.length != 1 || sender[0].isGroup()) {
            return false;
        }
        sender[0].validate();
        return isAuthorized(account, sender[0].getAddress());
    }

    public static boolean checkEnvelope(Connection connection, String address) throws IOException {
        if (!isEnabled() || !connection.getSession().isAuth()) {
            return true;
        }
        try {
            return respond(connection, configured().isAuthorized(connection.getSession().getUsername(), address));
        } catch (SQLException | RuntimeException e) {
            return unavailable(connection);
        }
    }

    public static boolean checkHeaders(Connection connection, EmailParser parser) throws IOException {
        if (parser == null || !parser.hasValidHeaders()) {
            connection.write(SmtpResponses.INVALID_HEADER_FRAMING_554);
            connection.markSmtpResponseSent();
            return false;
        }
        if (!isEnabled() || !connection.getSession().isAuth()) {
            return true;
        }
        try {
            return respond(connection, configured().isHeaderAuthorized(connection.getSession().getUsername(), parser));
        } catch (AddressException e) {
            return respond(connection, false);
        } catch (SQLException | RuntimeException e) {
            return unavailable(connection);
        }
    }

    private static boolean respond(Connection connection, boolean authorized) throws IOException {
        if (!authorized) {
            connection.write("550 5.7.1 Sender is not authorized for this account");
            connection.markSmtpResponseSent();
        }
        return authorized;
    }

    private static boolean unavailable(Connection connection) throws IOException {
        log.error("Sender authorization unavailable; refusing submission temporarily");
        connection.write("451 4.7.0 Sender authorization temporarily unavailable");
        connection.markSmtpResponseSent();
        return false;
    }
}
