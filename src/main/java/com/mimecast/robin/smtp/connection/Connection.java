package com.mimecast.robin.smtp.connection;

import com.mimecast.robin.config.client.LoggingConfig;
import com.mimecast.robin.config.server.ScenarioConfig;
import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.config.server.UserConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.smtp.EmailDelivery;
import com.mimecast.robin.smtp.EmailReceipt;
import com.mimecast.robin.smtp.io.LineInputStream;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.util.Sleep;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLSocket;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Optional;

/**
 * Connection controller.
 * <p>This is the single object that passes through all the implementations of the extensions used in a receipt or delivery.
 *
 * @see EmailDelivery
 * @see EmailReceipt
 */
public class Connection extends SmtpFoundation {
    private static final Logger log = LogManager.getLogger(Connection.class);

    /**
     * Session instance.
     */
    protected final Session session;

    /**
     * Connection server.
     */
    private String server = null;

    /**
     * ScenarioConfig instance.
     */
    private ScenarioConfig scenario = null;

    /**
     * [Client] Constructs a new Connection instance with given Session.
     *
     * @param session Session instance.
     */
    @SuppressWarnings("unchecked")
    public Connection(Session session) {
        this.session = session;

        if (Config.getProperties().hasProperty("logging")) {
            LoggingConfig logging = new LoggingConfig(Config.getProperties().getMapProperty("logging"));
            this.logData = logging.getData();
        }
    }

    /**
     * [Server] Constructs a new Connection instance with given Socket.
     *
     * @param socket Socket instance.
     * @throws IOException Unable to communicate.
     */
    public Connection(Socket socket) throws IOException {
        // Socket.
        this.socket = socket;
        setTimeout(DEFAULTTIMEOUT);

        // Streams.
        buildStreams();

        // Session.
        session = Factories.getSession();

        // Connection info.
        session.setAddr(socket.getLocalAddress().getHostName());
        session.setRdns(socket.getLocalAddress().getHostAddress());

        session.setFriendAddr(socket.getInetAddress().getHostAddress());
        session.setFriendRdns(socket.getInetAddress().getHostName());
    }

    /**
     * Connect to socket.
     *
     * @throws IOException Unable to communicate.
     */
    public void connect() throws IOException {
        if (StringUtils.isNotBlank(session.getBind())) {
            log.info("Binding to: {}", session.getBind());
            socket.bind(new InetSocketAddress(session.getBind(), 0));
        }

        int retry = session.getRetry() > 0 ? session.getRetry() : 1;

        if (session.getMx().isEmpty()) {
            throw new SmtpException("No MX to connect to");
        }

        for (int i = 0; i < retry; i++) {
            try {
                server = session.getMx().get(i % session.getMx().size());
                log.info("Connecting to: {}:{}", server, session.getPort());

                socket = new Socket();
                socket.connect(new InetSocketAddress(server, session.getPort()), this.session.getConnectTimeout());
                buildStreams();

                log.info("Connected to: {}:{}", server, session.getPort());

                setTimeout(session.getTimeout());
                String read = read("220");
                if (!read.startsWith("220")) {
                    if (i == retry - 1) {
                        session.getSessionTransactionList().addTransaction("SMTP", read, true);
                        throw new SmtpException("SMTP");
                    } else {
                        close(); // Retry.
                    }
                } else {
                    session.getSessionTransactionList().addTransaction("SMTP", read, false);
                    break;
                }
            } catch (Exception e) {
                if (i == retry - 1) {
                    throw e;
                }
                log.info("Connection error: {}", e.getMessage());
                Sleep.nap(session.getDelay() * 1000);
            }
        }
    }

    /**
     * Build input/output streams.
     *
     * @throws IOException Unable to communicate.
     */
    public void buildStreams() throws IOException {
        inc = new LineInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());
    }

    /**
     * Gets Session instance.
     *
     * @return Session instance.
     */
    public Session getSession() {
        return session;
    }

    /**
     * Gets SSL socket peer host.
     *
     * @return String.
     */
    public String getPeerHost() {
        return socket instanceof SSLSocket ? ((SSLSocket) socket).getSession().getPeerHost() : "";
    }

    /**
     * Gets connection server.
     *
     * @param server Server name.
     * @return Server string.
     */
    public Connection setServer(String server) {
        this.server = server;
        return this;
    }

    /**
     * Gets connection server.
     *
     * @return Server string.
     */
    public String getServer() {
        return server;
    }

    /**
     * [Server] Gets server username.
     *
     * @param username Username string.
     * @return Optional of UserConfig.
     */
    public Optional<UserConfig> getUser(String username) {
        return Config.getServer().getUser(username);
    }

    /**
     * Sets scenario.
     *
     * @param scenario Scenario instance.
     * @return Self.
     */
    public Connection setScenario(ScenarioConfig scenario) {
        this.scenario = scenario;
        return this;
    }

    /**
     * [Server] Gets scenarios for given HELO/EHLO.
     *
     * @return Optional of ScenarioConfig.
     */
    public Optional<ScenarioConfig> getScenario() {
        return scenario != null ? Optional.of(scenario) : Optional.ofNullable(Config.getServer())
                .map(ServerConfig::getScenarios)
                .map(s -> {
                    ScenarioConfig c = s.get(session.getEhlo());
                    return c != null ? c : s.get("*");
                });
    }

    /**
     * [Server] Reset connection.
     */
    @SuppressWarnings("EmptyMethod")
    public void reset() {
        // TODO Implement reset.
    }
}
