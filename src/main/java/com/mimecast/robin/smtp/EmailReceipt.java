package com.mimecast.robin.smtp;

import com.mimecast.robin.config.server.ListenerConfig;
import com.mimecast.robin.config.server.WebhookConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Extensions;
import com.mimecast.robin.scanners.rbl.RblChecker;
import com.mimecast.robin.scanners.rbl.RblResult;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.extension.Extension;
import com.mimecast.robin.smtp.extension.server.ServerData;
import com.mimecast.robin.smtp.extension.server.ServerMail;
import com.mimecast.robin.smtp.extension.server.ServerProcessor;
import com.mimecast.robin.smtp.extension.server.ServerRcpt;
import com.mimecast.robin.smtp.metrics.SmtpMetrics;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.verb.Verb;
import com.mimecast.robin.smtp.webhook.WebhookCaller;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Email receipt runnable.
 *
 * <p>This is used to create threads for incoming connections.
 * <p>A new instance will be constructed for every socket connection the server receives.
 */
@SuppressWarnings("WeakerAccess")
public class EmailReceipt implements Runnable {
    private static final Logger log = LogManager.getLogger(EmailReceipt.class);

    /**
     * Connection instance.
     */
    protected Connection connection;

    /**
     * Listener config instance.
     */
    private ListenerConfig config = new ListenerConfig();

    /**
     * Error limitation.
     * <p>Limits how many erroneous commands will be permitted.
     */
    private int errorLimit = 3;

    /**
     * Constructs a new EmailReceipt instance with given Connection instance.
     * <p>For testing purposes only.
     *
     * @param connection Connection instance.
     */
    EmailReceipt(Connection connection) {
        this.connection = connection;
    }

    /**
     * Constructs a new EmailReceipt instance with given socket.
     *
     * @param socket     Inbound socket.
     * @param config     Listener configuration instance.
     * @param secure     Secure (TLS) listener.
     * @param submission Submission (MSA) listener.
     */
    public EmailReceipt(Socket socket, ListenerConfig config, boolean secure, boolean submission) {
        try {
            connection = new Connection(socket);
            this.config = config;
            errorLimit = config.getErrorLimit();

            // Enable TLS handling if secure listener.
            if (secure) {
                connection.startTLS(false);
                connection.getSession().setStartTls(true);
                connection.buildStreams();
                connection.getSession().setTls(true);
                connection.getSession().setSecurePort(true);
            }

            // Set session direction depending on if submission port or not.
            connection.getSession().setDirection(submission ? EmailDirection.OUTBOUND : EmailDirection.INBOUND);
        } catch (IOException e) {
            log.info("Error initializing streams: {}", e.getMessage());
        }
    }

    /**
     * Server receipt runner.
     * <p>The loop begins after a connection is received and the welcome message sent.
     * <p>It will stop is processing any command returns false.
     * <p>False can be returned is there was a problem processing said command or from QUIT.
     * <p>The loop will also break if the syntax error limit is reached.
     * <p>Once the loop breaks the connection is closed.
     */
    public void run() {
        try {
            // Check client against RBLs and send appropriate greeting.
            // If blacklisted and inbound non-secure, send rejection.
            // Secure connections will perform RBL check at MAIL command.
            if (connection.getSession().isInbound() &&
                    !connection.getSession().isSecurePort() &&
                    !isReputableIp()) {
                // Send rejection message for blacklisted IP.
                connection.write(String.format(SmtpResponses.LISTED_CLIENT_550, connection.getSession().getUID()));
                return;
            } else {
                // Send normal welcome message for clean IPs.
                connection.write(String.format(SmtpResponses.GREETING_220, Config.getServer().getHostname(),
                        connection.getSession().getRdns(), connection.getSession().getDate()));
            }

            // Track successful connection.
            SmtpMetrics.incrementEmailReceiptStart();

            Verb verb;
            for (int i = 0; i < config.getTransactionsLimit(); i++) {
                String read = connection.read().trim();
                if (read.isEmpty()) {
                    log.error("Read empty, breaking.");
                    break;
                }
                verb = new Verb(read);

                // Don't process if error.
                if (!isError(verb)) process(verb);

                // Special handling for MAIL command on secure inbound connections.
                // Perform RBL check here once we know the connection is not outbound.
                // Secure port supports submission when authenticated.
                if (verb.getVerb().equalsIgnoreCase("mail") &&
                        connection.getSession().isInbound() &&
                        connection.getSession().isSecurePort() &&
                        !isReputableIp()) {
                    // Send rejection message for blacklisted IP.
                    connection.write(String.format(SmtpResponses.LISTED_CLIENT_550, connection.getSession().getUID()));
                    break;
                }

                // Break the loop.
                // Break if error limit reached.
                if (verb.getCommand().equalsIgnoreCase("quit") || errorLimit <= 0) {
                    if (errorLimit <= 0) {
                        log.warn("Error limit reached.");
                        SmtpMetrics.incrementEmailReceiptLimit();
                    }
                    break;
                }
            }
        } catch (Exception e) {
            SmtpMetrics.incrementEmailReceiptException(e.getClass().getSimpleName());
            log.info("Error reading/writing: {}", e.getMessage());
        } finally {
            if (Config.getServer().getStorage().getBooleanProperty("autodelete", true)) {
                connection.getSession().close();
            }
            connection.close();
        }
    }

    /**
     * Performs RBL check on client IP.
     *
     * @return true if blacklist check passed or not enabled, false if blacklisted.
     */
    private boolean isReputableIp() {
        String clientIp = connection.getSession().getFriendAddr();
        boolean isBlacklisted = false;
        String blacklistingRbl = null;

        // Only perform RBL check if enabled in configuration.
        if (Config.getServer().getRblConfig().isEnabled()) {
            log.debug("Checking IP {} against RBL lists", clientIp);

            List<String> rblProviders = Config.getServer().getRblConfig().getProviders();
            int timeoutSeconds = Config.getServer().getRblConfig().getTimeoutSeconds();

            // Check the client IP against configured RBL providers.
            List<RblResult> results = RblChecker.checkIpAgainstRbls(clientIp, rblProviders, timeoutSeconds);

            // Find the first RBL that lists this IP (if any).
            Optional<RblResult> blacklisted = results.stream()
                    .filter(RblResult::isListed)
                    .findFirst();

            if (blacklisted.isPresent()) {
                isBlacklisted = true;
                blacklistingRbl = blacklisted.get().getRblProvider();
                log.info("Client IP {} is blacklisted by {}", clientIp, blacklistingRbl);
            }
        }

        // Update session with RBL status and provider.
        connection.getSession()
                .setFriendInRbl(isBlacklisted)
                .setFriendRbl(blacklistingRbl);

        // Send appropriate greeting or rejection based on RBL status and enablement.
        if (isBlacklisted && Config.getServer().getRblConfig().isRejectEnabled()) {
            // Track rejected connections due to RBL listing.
            SmtpMetrics.incrementEmailRblRejection();
        }

        return !isBlacklisted;
    }

    /**
     * Server extension processor.
     *
     * @param verb Verb instance.
     * @return Boolean.
     * @throws IOException Unable to communicate.
     */
    private boolean isError(Verb verb) throws IOException {
        if (verb.isError()) {
            connection.write(SmtpResponses.SYNTAX_ERROR_500);
            errorLimit--;
            return true;
        }

        return false;
    }

    /**
     * Server extension processor.
     *
     * @param verb Verb instance.
     * @throws IOException Unable to communicate.
     */
    private void process(Verb verb) throws IOException {
        if (Extensions.isExtension(verb)) {
            Optional<Extension> opt = Extensions.getExtension(verb);
            if (opt.isPresent()) {
                // Call webhook before processing extension.
                if (!processWebhook(verb)) {
                    return; // Webhook intercepted processing.
                }

                ServerProcessor server = opt.get().getServer();

                // Set limit if applicable.
                if (server instanceof ServerMail) {
                    ((ServerMail) server).setEnvelopeLimit(config.getEnvelopeLimit());
                }
                if (server instanceof ServerRcpt) {
                    ((ServerRcpt) server).setRecipientsLimit(config.getRecipientsLimit());
                }
                if (server instanceof ServerData) {
                    ((ServerData) server).setEmailSizeLimit(config.getEmailSizeLimit());
                }

                server.process(connection, verb);
            }
        } else {
            errorLimit--;
            if (errorLimit == 0) {
                log.warn("Error limit reached.");
                return;
            }

            connection.write(SmtpResponses.UNRECOGNIZED_CMD_500);
        }
    }

    /**
     * Process webhook for extension if configured.
     *
     * @param verb Verb instance.
     * @return True to continue processing, false to stop.
     * @throws IOException Unable to communicate.
     */
    private boolean processWebhook(Verb verb) throws IOException {
        try {
            Map<String, WebhookConfig> webhooks = Config.getServer().getWebhooks();
            String extensionKey = verb.getKey().toLowerCase();

            if (webhooks.containsKey(extensionKey)) {
                WebhookConfig config = webhooks.get(extensionKey);

                if (!config.isEnabled()) {
                    return true; // Continue processing.
                }

                log.debug("Calling webhook for extension: {}", extensionKey);
                WebhookCaller.WebhookResponse response = WebhookCaller.call(config, connection, verb);

                // Check if webhook returned a custom SMTP response.
                String smtpResponse = WebhookCaller.extractSmtpResponse(response.getBody());
                if (smtpResponse != null && !smtpResponse.isEmpty()) {
                    connection.write(smtpResponse);
                    return false; // Stop processing, webhook provided response.
                }

                // Check response status.
                if (!response.isSuccess()) {
                    if (config.isIgnoreErrors()) {
                        log.warn("Webhook failed but ignoring errors: {}", response.getStatusCode());
                        return true; // Continue processing despite error.
                    } else {
                        // Send 451 temporary error.
                        connection.write(String.format(SmtpResponses.INTERNAL_ERROR_451, connection.getSession().getUID()));
                        return false; // Stop processing.
                    }
                }

                // 200 OK - continue processing.
                return true;
            }
        } catch (Exception e) {
            log.error("Error processing webhook: {}", e.getMessage(), e);
            connection.write(String.format(SmtpResponses.INTERNAL_ERROR_451, connection.getSession().getUID()));
            return false;
        }

        return true; // No webhook configured, continue processing.
    }
}
