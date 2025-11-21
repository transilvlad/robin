package com.mimecast.robin.storage;

import com.mimecast.robin.bots.BotFactory;
import com.mimecast.robin.bots.BotProcessor;
import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.main.Server;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.ChaosHeaders;
import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.queue.relay.RelayMessage;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
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
        if (config.getStorage().getBooleanProperty("enabled")) {
            if (PathUtils.makePath(getPath())) {
                stream = new FileOutputStream(getFile());
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
                stream.flush();
                stream.close();

                // Parse email for further processing.
                try (EmailParser emailParser = new EmailParser(getFile()).parse()) {
                    parser = emailParser;

                    // Rename file if X-Robin-Filename header exists and feature enabled.
                    if (!config.getStorage().getBooleanProperty("disableRenameHeader")) {
                        rename();
                    }

                    // Set email path to current envelope if any.
                    if (!connection.getSession().getEnvelopes().isEmpty()) {
                        connection.getSession().getEnvelopes().getLast().setFile(getFile());
                    }
                    log.info("Storage file saved to: {}", getFile());

                    // Run storage processors.
                    for (Callable<StorageProcessor> storageProcessor : Factories.getStorageProcessors()) {
                        try {
                            StorageProcessor processor = storageProcessor.call();

                            // Check for chaos headers and bypass processor if matched.
                            if (shouldBypassProcessor(processor)) {
                                continue;
                            }

                            if (!processor.process(connection, parser)) {
                                return false;
                            }
                        } catch (Exception e) {
                            log.error("Storage processor error: {}", e.getMessage());
                            return false;
                        }
                    }

                    // Process bot addresses if any
                    processBotAddresses(connection, parser);

                    // Relay email if X-Robin-Relay or relay configuration or direction outbound enabled.
                    relay();
                }
            }
        } catch (IOException e) {
            log.error("Storage unable to store the email: {}", e.getMessage());
            return false;
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
            Path target = Paths.get(getPath(), header.getValue());

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
     * Determines if a storage processor should be bypassed based on chaos headers.
     * <p>Checks if there's a chaos header that matches the processor class name
     * and should bypass the processor call based on the header parameters.
     *
     * @param processor The storage processor to check.
     * @return True if processor should be bypassed, false if processor should run normally.
     */
    private boolean shouldBypassProcessor(StorageProcessor processor) {
        // Early return if chaos headers are not enabled to avoid unnecessary object creation.
        if (!Config.getServer().isChaosHeaders()) {
            return false;
        }

        ChaosHeaders chaosHeaders = new ChaosHeaders(parser);

        if (!chaosHeaders.hasHeaders()) {
            return false;
        }
        
        String processorClassName = processor.getClass().getSimpleName();

        // Check for chaos headers matching LocalStorageClient.
        // Format: X-Robin-Chaos: LocalStorageClient; processor=ProcessorClassName; return=true/false
        for (MimeHeader header : chaosHeaders.getByValue(ChaosHeaders.TARGET_LOCAL_STORAGE_CLIENT)) {
            String processorParam = header.getParameter("processor");
            String returnParam = header.getParameter("return");

            // The processor parameter should match the processor class name to bypass.
            // Format: processor=ProcessorClassName (e.g., processor=AVStorageProcessor, processor=SpamStorageProcessor).
            if (processorParam != null && processorParam.equals(processorClassName)) {
                boolean shouldBypass = returnParam != null && Boolean.parseBoolean(returnParam);
                log.debug("Chaos header bypassing {} with bypass={}", processorClassName, shouldBypass);
                return shouldBypass; // true = bypass processor and continue, false = bypass processor and fail.
            }
        }

        return false; // No matching chaos header, processor should run normally.
    }

    /**
     * Processes bot addresses by submitting them to the bot thread pool.
     * <p>Each bot address is processed in a separate thread to avoid blocking.
     *
     * @param connection  Connection instance.
     * @param emailParser Parsed email instance.
     */
    private void processBotAddresses(Connection connection, EmailParser emailParser) {
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

        // Process each bot address
        Map<String, List<String>> botAddresses = envelope.getBotAddresses();
        for (Map.Entry<String, List<String>> entry : botAddresses.entrySet()) {
            String address = entry.getKey();
            List<String> botNames = entry.getValue();

            for (String botName : botNames) {
                Optional<BotProcessor> botOpt = BotFactory.getBot(botName);
                if (botOpt.isPresent()) {
                    BotProcessor bot = botOpt.get();
                    
                    // Clone the session to avoid race conditions
                    // The bot processing happens asynchronously and the original connection/session
                    // may be cleaned up or modified by the time the bot processes it.
                    // We create a new connection with the cloned session for thread safety.
                    com.mimecast.robin.smtp.session.Session sessionClone = connection.getSession().clone();
                    Connection connectionCopy = new Connection(sessionClone);
                    
                    // Submit bot processing to thread pool
                    botExecutor.submit(() -> {
                        try {
                            bot.process(connectionCopy, emailParser, address);
                        } catch (Exception e) {
                            log.error("Error processing bot {} for address {}: {}",
                                    botName, address, e.getMessage(), e);
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
     * Relay email to another server by header or config.
     * <p>Will relay email to provided server.
     */
    private void relay() {
        if (!connection.getSession().getEnvelopes().isEmpty()) {
            new RelayMessage(connection, parser).relay();
        }
    }
}
