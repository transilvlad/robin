package com.mimecast.robin.storage;

import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.mime.parts.FileMimePart;
import com.mimecast.robin.mime.parts.MimePart;
import com.mimecast.robin.queue.PersistentQueue;
import com.mimecast.robin.queue.QueueFiles;
import com.mimecast.robin.queue.RelayQueueCron;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.queue.bounce.BounceMessageGenerator;
import com.mimecast.robin.queue.relay.DovecotLdaDelivery;
import com.mimecast.robin.queue.relay.RelayMessage;
import com.mimecast.robin.scanners.ClamAVClient;
import com.mimecast.robin.scanners.RspamdClient;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.metrics.SmtpMetrics;
import com.mimecast.robin.smtp.transaction.EnvelopeTransactionList;
import com.mimecast.robin.util.PathUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Optional;

/**
 * Local storage client implementation.
 *
 * <p>Saves files on disk.
 */
public class LocalStorageClient implements StorageClient {
    protected static final Logger log = LogManager.getLogger(LocalStorageClient.class);

    /**
     * Enablement.
     */
    protected ServerConfig config = Config.getServer();

    /**
     * Date.
     */
    protected String now = new SimpleDateFormat("yyyyMMdd", Config.getProperties().getLocale()).format(new Date());

    /**
     * Connection instance.
     */
    protected Connection connection;

    /**
     * Save file name.
     */
    protected String fileName;

    /**
     * Save file path.
     */
    protected String path;

    /**
     * EmailParser instance.
     */
    protected EmailParser parser;

    /**
     * Save file output stream.
     */
    protected OutputStream stream = NullOutputStream.INSTANCE;

    /**
     * Sets file extension.
     *
     * @param extension File extension.
     * @return Self.
     */
    public LocalStorageClient setExtension(String extension) {
        if (extension == null) {
            extension = ".dat";
        } else if (!extension.startsWith(".")) {
            extension = "." + extension;
        }

        fileName = now + "." + connection.getSession().getUID() + extension;

        return this;
    }

    /**
     * Sets connection.
     *
     * @param connection Connection instance.
     * @return Self.
     */
    @Override
    public LocalStorageClient setConnection(Connection connection) {
        this.connection = connection;
        path = config.getStorage().getStringProperty("path", "/tmp/store");

        // Append first recipient domain/address to path
        if (connection != null && !connection.getSession().getEnvelopes().isEmpty() && !connection.getSession().getEnvelopes().getLast().getRcpts().isEmpty()) {
            String[] splits = connection.getSession().getEnvelopes().getLast().getRcpts().getFirst().split("@");
            if (splits.length == 2) {
                path = Paths.get(
                        path,
                        PathUtils.normalize(splits[1]),
                        PathUtils.normalize(splits[0])
                ).toString();
            }
        }

        return this;
    }

    /**
     * Gets file output stream.
     *
     * @return OutputStream instance.
     */
    @Override
    public OutputStream getStream() throws FileNotFoundException {
        if (config.getStorage().getBooleanProperty("enabled")) {
            if (PathUtils.makePath(path)) {
                stream = new FileOutputStream(Paths.get(path, fileName).toString());
            } else {
                log.error("Storage path could not be created");
            }
        } else {
            stream = NullOutputStream.INSTANCE;
        }

        return stream;
    }

    /**
     * Gets file path.
     *
     * @return String.
     */
    @Override
    public String getFile() {
        return Paths.get(path, fileName).toString();
    }

    /**
     * Saves file.
     */
    @Override
    public void save() {
        if (config.getStorage().getBooleanProperty("enabled")) {
            try {
                stream.close();

                // Save email path to current envelope if any.
                if (!connection.getSession().getEnvelopes().isEmpty()) {
                    connection.getSession().getEnvelopes().getLast().setFile(getFile());
                }

                // Scan email with ClamAV.
                if (!isClean(Files.readAllBytes(Paths.get(getFile())), "email")) {
                    return;
                }

                // Scan email with Rspamd.
                if (!isHam(Files.readAllBytes(Paths.get(getFile())))) {
                    return;
                }

                // Parse email for further processing.
                parser = new EmailParser(getFile()).parse();

                // Scan each non-text part with ClamAV for improved results if enabled.
                if (config.getClamAV().getBooleanProperty("scanAttachments")) {
                    for (MimePart part : parser.getParts()) {
                        if (part instanceof FileMimePart) {
                            String partInfo = part.getHeader("content-type") != null ? part.getHeader("content-type").getValue() : "attachment";
                            boolean isClean = isClean(part.getBytes(), partInfo);
                            ((FileMimePart) part).getFile().delete();
                            if (!isClean) {
                                return;
                            }
                        }
                    }
                }

                // Rename file if X-Robin-Filename header exists and feature enabled.
                if (!config.getStorage().getBooleanProperty("disableRenameHeader")) {
                    rename();
                }

                // Save envelope file path.
                if (!connection.getSession().getEnvelopes().isEmpty()) {
                    connection.getSession().getEnvelopes().getLast().setFile(getFile());
                }

                // Save to Dovecot LDA if enabled.
                saveToDovecotLda();

                // Relay email if X-Robin-Relay or relay configuration or direction outbound enabled.
                relay();

            } catch (IOException e) {
                log.error("Storage unable to store the email: {}", e.getMessage());
            }

            try {
                stream.flush();
                stream.close();
                log.info("Storage file saved to: {}", getFile());

            } catch (IOException e) {
                log.error("Storage file not flushed/closed: {}", e.getMessage());
            }
        }
    }

    /**
     * Scan with ClamAV.
     *
     * @param bytes File bytes.
     * @param part  Part description for logging.
     * @return Boolean false if infected and to be dropped.
     * @throws IOException Unable to access file.
     */
    private boolean isClean(byte[] bytes, String part) throws IOException {
        BasicConfig clamAVConfig = config.getClamAV();
        if (clamAVConfig.getBooleanProperty("enabled")) {
            ClamAVClient clamAVClient = new ClamAVClient(
                    clamAVConfig.getStringProperty("host", "localhost"),
                    clamAVConfig.getLongProperty("port", 3310L).intValue()
            );

            if (clamAVClient.isInfected(bytes)) {
                log.warn("Virus found in {}: {}", part, clamAVClient.getViruses());
                String onVirus = clamAVConfig.getStringProperty("onVirus", "reject");
                SmtpMetrics.incrementEmailVirusRejection();

                if ("reject".equalsIgnoreCase(onVirus)) {
                    connection.write(String.format(SmtpResponses.VIRUS_FOUND_550, connection.getSession().getUID()));
                } else if ("discard".equalsIgnoreCase(onVirus)) {
                    log.warn("Virus found, discarding.");
                }

                return false;
            } else {
                log.info("AV scan clean for {}", part.replaceAll("\\s+", " "));
            }
        }

        return true;
    }

    /**
     * Scan with Rspamd.
     *
     * @param bytes File bytes.
     * @return Boolean false if spam/phishing detected and to be dropped.
     * @throws IOException Unable to access file.
     */
    private boolean isHam(byte[] bytes) throws IOException {
        BasicConfig rspamdConfig = config.getRspamd();
        if (rspamdConfig.getBooleanProperty("enabled")) {
            RspamdClient rspamdClient = new RspamdClient(
                    rspamdConfig.getStringProperty("host", "localhost"),
                    rspamdConfig.getLongProperty("port", 11333L).intValue())
                    .setEmailDirection(connection.getSession().getDirection())
                    .setSpfScanEnabled(rspamdConfig.getBooleanProperty("spfScanEnabled"))
                    .setDkimScanEnabled(rspamdConfig.getBooleanProperty("dkimScanEnabled"))
                    .setDmarcScanEnabled(rspamdConfig.getBooleanProperty("dmarcScanEnabled"));

            if (rspamdClient.isSpam(bytes, rspamdConfig.getDoubleProperty("requiredScore", 7.0))) {
                double score = rspamdClient.getScore();
                log.warn("Spam/phishing detected in {} with score {}: {}", getFile(), score, rspamdClient.getSymbols());
                String onSpam = rspamdConfig.getStringProperty("onSpam", "reject");
                SmtpMetrics.incrementEmailSpamRejection();

                if ("reject".equalsIgnoreCase(onSpam)) {
                    connection.write(String.format(SmtpResponses.SPAM_FOUND_550, connection.getSession().getUID()));
                } else if ("discard".equalsIgnoreCase(onSpam)) {
                    log.warn("Spam/phishing detected, discarding.");
                }

                return false;
            } else {
                log.info("Spam scan clean with score {}", rspamdClient.getScore());
            }
        }

        return true;
    }

    /**
     * Rename filename.
     * <p>Will parse and lookup if an X-Robin-Filename header exists and use its value as a filename.
     *
     * @throws IOException Unable to delete file.
     */
    private void rename() throws IOException {
        Optional<MimeHeader> optional = parser.getHeaders().get("x-robin-filename");
        if (optional.isPresent()) {
            MimeHeader header = optional.get();

            String source = getFile();
            Path target = Paths.get(path, header.getValue());

            if (StringUtils.isNotBlank(header.getValue())) {
                if (Files.deleteIfExists(target)) {
                    log.info("Storage deleted existing file before rename");
                }

                if (new File(source).renameTo(new File(target.toString()))) {
                    fileName = header.getValue();
                    log.info("Storage moved file to: {}", getFile());
                }
            }
        }
    }

    /**
     * Save email to Dovecot LDA directly.
     */
    private void saveToDovecotLda() throws IOException {
        if (config.getDovecot().getBooleanProperty("saveToDovecotLda")) {
            getDovecotLdaDeliveryInstance().send();

            // If there are multiple recipients and one fails bounce recipient instead of throwing an exception.
            EnvelopeTransactionList envelopeTransactionList = connection.getSession().getSessionTransactionList().getEnvelopes().getLast();
            if (!envelopeTransactionList.getErrors().isEmpty()) {
                if (envelopeTransactionList.getRecipients() != envelopeTransactionList.getFailedRecipients()) {
                    connection.getSession().getEnvelopes().getLast().setRcpts(connection.getSession().getSessionTransactionList().getEnvelopes().getLast().getFailedRecipients());

                    for (String recipient : connection.getSession().getEnvelopes().getLast().getRcpts()) {
                        // Generate bounce email.
                        BounceMessageGenerator bounce = new BounceMessageGenerator(new RelaySession(connection.getSession()), recipient);

                        // Build the session.
                        RelaySession relaySessionBounce = new RelaySession(Factories.getSession())
                                .setProtocol("esmtp");

                        // Create the envelope.
                        MessageEnvelope envelope = new MessageEnvelope()
                                .setMail("mailer-daemon@" + config.getHostname())
                                .setRcpt(recipient)
                                .setBytes(bounce.getStream().toByteArray());
                        relaySessionBounce.getSession().addEnvelope(envelope);

                        // Queue bounce for delivery using runtime-configured queue file (fallback to default).
                        File queueFile = new File(config.getQueue().getStringProperty(
                                "queueFile",
                                RelayQueueCron.QUEUE_FILE.getAbsolutePath()
                        ));

                        // Persist any envelope files (no-op for bytes-only envelopes) before enqueue.
                        QueueFiles.persistEnvelopeFiles(relaySessionBounce);

                        PersistentQueue.getInstance(queueFile)
                                .enqueue(relaySessionBounce);
                    }

                } else {
                    throw new IOException("Storage unable to save to Dovecot LDA");
                }
            }
        }
    }

    /**
     * Get DovecotLdaDelivery instance.
     * <p>Can be overridden for testing/mocking purposes.
     *
     * @return DovecotLdaDelivery instance.
     */
    protected DovecotLdaDelivery getDovecotLdaDeliveryInstance() {
        RelaySession relaySession = new RelaySession(connection.getSession());
        if (connection.getSession().isOutbound()) {
            relaySession.setMailbox(config.getDovecot().getStringProperty("outboundMailbox", "Sent"));
        }

        return new DovecotLdaDelivery(relaySession);
    }

    /**
     * Relay email to another server by header or config.
     * <p>Will relay email to provided server.
     */
    private void relay() {
        if (!connection.getSession().getEnvelopes().isEmpty()) {
            new RelayMessage(connection, parser).relay();
        }
    }
}
