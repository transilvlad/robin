package com.mimecast.robin.storage;

import com.mimecast.robin.bots.BotProcessor;
import com.mimecast.robin.config.server.BotConfig;
import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.main.Server;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.queue.relay.RelayMessage;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.MessageSource;
import com.mimecast.robin.smtp.RefCountedFileMessageSource;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

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
        path = Paths.get(config.getStorage().getStringProperty("path", "/tmp/store"), "tmp").toString();

        return this;
    }

    /**
     * Gets file output stream.
     *
     * @return OutputStream instance.
     */
    @Override
    public OutputStream getStream() throws FileNotFoundException {
        if (!(stream instanceof NullOutputStream) && stream != null) {
            return stream;
        }

        if (config.getStorage().getBooleanProperty("enabled")) {
            if (PathUtils.makePath(getPath())) {
                long threshold = config.getStorage().getLongProperty("messageBufferMaxBytes", 1024L * 1024L);
                stream = new MessageBufferOutputStream(threshold, Path.of(getFile()));
            } else {
                log.error("Storage path could not be created");
            }
        } else {
            stream = NullOutputStream.INSTANCE;
        }

        return stream;
    }

    /**
     * Gets path.
     *
     * @return String.
     */
    @Override
    public String getPath() {
        return path;
    }

    /**
     * Gets file path.
     *
     * @return String.
     */
    @Override
    public String getFile() {
        return Paths.get(getPath(), fileName).toString();
    }

    /**
     * Saves file.
     *
     * @return Boolean.
     */
    @Override
    public boolean save() {
        try {
            if (config.getStorage().getBooleanProperty("enabled")) {
                // For bot addresses, force spill to file for thread-safe concurrent access.
                MessageEnvelope envelope = getCurrentEnvelope();
                if (envelope != null && envelope.hasBotAddresses() && stream instanceof MessageBufferOutputStream bufferStream) {
                    log.debug("Forcing spill to file for bot addresses");
                    bufferStream.forceSpillToFile();
                } else {
                    log.debug("No forceSpill: envelope={}, hasBotAddresses={}, streamType={}",
                            envelope != null, envelope != null && envelope.hasBotAddresses(),
                            stream != null ? stream.getClass().getSimpleName() : "null");
                }

                stream.flush();
                stream.close();

                parser = null;
                if (envelope != null) {
                    envelope.setFile(getFile());
                    if (stream instanceof MessageBufferOutputStream bufferStream) {
                        MessageSource source = bufferStream.toMessageSource();
                        log.debug("Created message source: {} ({} bytes)", 
                                source.getClass().getSimpleName(), source.size());
                        envelope.setMessageSource(source);
                    } else if (Files.exists(Path.of(getFile()))) {
                        envelope.setMessageSource(new RefCountedFileMessageSource(Path.of(getFile())));
                    }
                }

                boolean parseHeadersOnly = shouldParseHeadersOnly(envelope);
                boolean parseFullEmail = isFullEmailParseRequired();
                if (parseHeadersOnly || parseFullEmail) {
                    try (InputStream input = envelope != null ? envelope.openMessageStream() : null;
                         EmailParser emailParser = input != null
                                 ? new EmailParser(input).parse(!parseFullEmail)
                                 : new EmailParser(getFile()).parse(!parseFullEmail)) {
                        parser = emailParser;

                        if (!config.getStorage().getBooleanProperty("disableRenameHeader")) {
                            rename(envelope);
                        }

                        if (envelope != null && envelope.hasBotAddresses()) {
                            copyBotHeaders(envelope);
                        }

                        if (!runStorageProcessors()) {
                            return false;
                        }
                    }
                } else if (!runStorageProcessors()) {
                    return false;
                }

                log.info("Storage file saved to: {}", getFile());

                // Process bot addresses if any.
                // Bots can access email content via envelope.openMessageStream() and create their own parser.
                // Reference-counted message sources ensure the file is not deleted until all consumers are done.
                processBotAddresses(connection);

                // Relay email if X-Robin-Relay or relay configuration or direction outbound enabled.
                relay();
            }
        } catch (IOException e) {
            log.error("Storage unable to store the email: {}", e.getMessage());
            return false;
        }

        return true;
    }

    private boolean runStorageProcessors() {
        for (Callable<StorageProcessor> storageProcessor : Factories.getStorageProcessors()) {
            try {
                StorageProcessor processor = storageProcessor.call();
                if (!processor.process(connection, parser)) {
                    return false;
                }
            } catch (Exception e) {
                log.error("Storage processor error: {}", e.getMessage());
                return false;
            }
        }
        return true;
    }

    private MessageEnvelope getCurrentEnvelope() {
        if (connection.getSession().getEnvelopes().isEmpty()) {
            return null;
        }
        return connection.getSession().getEnvelopes().getLast();
    }

    private boolean shouldParseHeadersOnly(MessageEnvelope envelope) {
        return Config.getServer().isChaosHeaders()
                || !config.getStorage().getBooleanProperty("disableRenameHeader")
                || (envelope != null && envelope.hasBotAddresses());
    }

    private boolean isFullEmailParseRequired() {
        var clamAvConfig = config.getClamAV();
        return clamAvConfig.getBooleanProperty("enabled")
                && clamAvConfig.getBooleanProperty("scanAttachments");
    }

    private void copyBotHeaders(MessageEnvelope envelope) {
        Optional<MimeHeader> replyTo = parser.getHeaders().get("Reply-To");
        replyTo.ifPresent(header -> envelope.addHeader("X-Parsed-Reply-To", header.getValue()));

        Optional<MimeHeader> from = parser.getHeaders().get("From");
        from.ifPresent(header -> envelope.addHeader("X-Parsed-From", header.getValue()));
    }

    /**
     * Rename filename.
     * <p>Will parse and lookup if an X-Robin-Filename header exists and use its value as a filename.
     *
     * @throws IOException Unable to delete file.
     */
    private void rename(MessageEnvelope envelope) throws IOException {
        Optional<MimeHeader> optional = parser.getHeaders().get("x-robin-filename");
        if (optional.isPresent()) {
            MimeHeader header = optional.get();

            String source = getFile();
            Path target = Paths.get(getPath(), header.getValue());

            if (StringUtils.isNotBlank(header.getValue())) {
                fileName = header.getValue();
                if (envelope != null) {
                    envelope.setFile(target.toString());
                }

                if (StringUtils.isNotBlank(source) && Files.exists(Path.of(source))) {
                    if (Files.deleteIfExists(target)) {
                        log.info("Storage deleted existing file before rename");
                    }

                    if (new File(source).renameTo(new File(target.toString()))) {
                        if (envelope != null) {
                            envelope.setMessageSource(new RefCountedFileMessageSource(target));
                        }
                        log.info("Storage moved file to: {}", getFile());
                    }
                }
            }
        }
    }


    /**
     * Processes bot addresses by submitting them to the bot thread pool.
     * <p>Each bot address is processed in a separate thread to avoid blocking.
     * <p>Bots can access the email content via {@code envelope.openMessageStream()} and create
     * their own parser if needed. Reference-counted message sources ensure the backing file
     * is not deleted until all consumers (main thread + bot threads) have released their references.
     *
     * @param connection Connection instance.
     */
    private void processBotAddresses(Connection connection) {
        if (connection.getSession().getEnvelopes().isEmpty()) {
            return;
        }

        MessageEnvelope envelope = connection.getSession().getEnvelopes().getLast();
        if (!envelope.hasBotAddresses()) {
            return;
        }

        // Get bot executor service
        ExecutorService botExecutor = Server.getBotExecutor();
        if (botExecutor == null) {
            log.warn("Bot executor not initialized, skipping bot processing");
            return;
        }

        // Get bot definitions for config lookup.
        List<BotConfig.BotDefinition> botDefinitions = Config.getServer().getBots().getBots();

        // Process each bot address
        Map<String, List<String>> botAddresses = envelope.getBotAddresses();
        for (Map.Entry<String, List<String>> entry : botAddresses.entrySet()) {
            String address = entry.getKey();
            List<String> botNames = entry.getValue();

            for (String botName : botNames) {
                Optional<BotProcessor> botOpt = Factories.getBot(botName);
                if (botOpt.isPresent()) {
                    BotProcessor bot = botOpt.get();
                    
                    // Find matching bot definition for this address.
                    BotConfig.BotDefinition botDefinition = findBotDefinition(botDefinitions, address, botName);
                    
                    // Clone the session to avoid race conditions.
                    // The bot processing happens asynchronously and the original connection/session
                    // may be cleaned up or modified by the time the bot processes it.
                    // We create a new connection with the cloned session for thread safety.
                    // The clone acquires a reference to any RefCountedFileMessageSource, ensuring
                    // the backing file is not deleted until all consumers are done.
                    Session sessionClone = connection.getSession().clone();
                    Connection connectionCopy = new Connection(sessionClone);
                    
                    // Submit bot processing to thread pool.
                    // Each bot gets its own EmailParser created from the envelope's message stream.
                    botExecutor.submit(() -> {
                        InputStream input = null;
                        try {
                            // Create a fresh parser for this bot from the saved file.
                            // The in-memory message source may be incomplete due to timing,
                            // so we prefer the saved file which contains the complete message.
                            MessageEnvelope botEnvelope = sessionClone.getEnvelopes().getLast();
                            String savedFile = botEnvelope.getFile();
                            
                            if (savedFile != null && !savedFile.isEmpty()) {
                                File file = new File(savedFile);
                                if (file.exists() && file.canRead()) {
                                    log.debug("Bot using saved file: {} ({} bytes)", savedFile, file.length());
                                    input = new FileInputStream(file);
                                }
                            }
                            
                            // Fall back to message source if file not available.
                            if (input == null) {
                                log.debug("Bot envelope file: {}, messageSource: {}", 
                                        botEnvelope.getFile(), botEnvelope.getMessageSource());
                                if (botEnvelope.getMessageSource() != null) {
                                    log.debug("Bot messageSource size: {} bytes", botEnvelope.getMessageSource().size());
                                }
                                input = botEnvelope.openMessageStream();
                            }
                            
                            // Create parser but don't parse yet - let the bot handle full parsing.
                            EmailParser botParser = input != null ? new EmailParser(input) : null;
                            bot.process(connectionCopy, botParser, address, botDefinition);
                        } catch (Exception e) {
                            log.error("Error processing bot {} for address {}: {}",
                                    botName, address, e.getMessage(), e);
                        } finally {
                            // Close input stream if open.
                            if (input != null) {
                                try {
                                    input.close();
                                } catch (Exception ignored) {
                                }
                            }
                            // Release the reference to message sources (decrements ref count).
                            sessionClone.close();
                        }
                    });
                    log.info("Submitted bot {} for processing address: {}", botName, address);
                } else {
                    log.warn("Bot {} not found in factory for address: {}", botName, address);
                }
            }
        }
    }

    /**
     * Finds the bot definition matching the given address and bot name.
     *
     * @param definitions List of bot definitions.
     * @param address     Email address to match.
     * @param botName     Bot name to match.
     * @return Matching bot definition, or null if not found.
     */
    private BotConfig.BotDefinition findBotDefinition(List<BotConfig.BotDefinition> definitions, String address, String botName) {
        for (BotConfig.BotDefinition def : definitions) {
            if (def.getBotName().equals(botName) && def.matchesAddress(address)) {
                return def;
            }
        }
        return null;
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
