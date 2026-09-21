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
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    private record BotDispatch(String address, String botName) {
    }

    private record BotInvocation(
            BotDispatch dispatch,
            BotProcessor bot,
            BotConfig.BotDefinition definition) {
    }

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

                        if (isRenameHeaderActive()) {
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
                || isRenameHeaderActive()
                || (envelope != null && envelope.hasBotAddresses());
    }

    /**
     * Is the X-Robin-Filename rename feature active.
     * <p>Requires both the server-wide opt-in ({@code renameHeaderEnabled}, default false)
     * and that it hasn't been explicitly disabled for this storage config.
     *
     * @return Boolean.
     */
    private boolean isRenameHeaderActive() {
        return Config.getServer().isRenameHeaderEnabled()
                && !config.getStorage().getBooleanProperty("disableRenameHeader");
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
     * <p>The header value is reduced to a bare filename (no directory components), so it can only
     * ever target a file inside {@link #getPath()}.
     *
     * @throws IOException Unable to delete file.
     */
    private void rename(MessageEnvelope envelope) throws IOException {
        Optional<MimeHeader> optional = parser.getHeaders().get("x-robin-filename");
        if (optional.isPresent()) {
            String safeName = PathUtils.safeFileName(optional.get().getValue());

            if (StringUtils.isNotBlank(safeName)) {
                String source = getFile();
                Path target = Paths.get(getPath(), safeName);

                fileName = safeName;
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
     * <p>All bots matched for one message run sequentially in one asynchronous task. The message
     * is opened and parsed once, while each bot receives an isolated session clone.
     * Reference-counted message sources ensure the backing file is not deleted until the batch
     * has finished processing.
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

        Map<String, BotDispatch> dispatches = new LinkedHashMap<>();
        Map<String, List<String>> botAddresses = envelope.getBotAddresses();
        for (Map.Entry<String, List<String>> entry : botAddresses.entrySet()) {
            String address = entry.getKey();
            for (String botName : entry.getValue()) {
                dispatches.putIfAbsent(botName.toLowerCase(Locale.ROOT), new BotDispatch(address, botName));
            }
        }

        List<BotInvocation> invocations = new ArrayList<>();
        for (BotDispatch dispatch : dispatches.values()) {
            String address = dispatch.address();
            String botName = dispatch.botName();
            Optional<BotProcessor> botOpt = Factories.getBot(botName);
            if (botOpt.isPresent()) {
                BotConfig.BotDefinition botDefinition = findBotDefinition(botDefinitions, address, botName);
                invocations.add(new BotInvocation(dispatch, botOpt.get(), botDefinition));
            } else {
                log.warn("Bot {} not found in factory for address: {}", botName, address);
            }
        }

        if (invocations.isEmpty()) {
            return;
        }

        // Retain the message before returning to the SMTP thread. Each bot gets a child clone
        // from this retained session so bot-specific session changes cannot leak between bots.
        String sessionUid = connection.getSession().getUID();
        Session batchSession = connection.getSession().clone();
        try {
            botExecutor.submit(() -> {
                long startedAt = System.nanoTime();
                int succeeded = 0;
                int failed = 0;
                InputStream input = null;
                EmailParser botParser = null;
                try {
                    MessageEnvelope botEnvelope = batchSession.getEnvelopes().getLast();
                    String savedFile = botEnvelope.getFile();

                    if (savedFile != null && !savedFile.isEmpty()) {
                        File file = new File(savedFile);
                        if (file.exists() && file.canRead()) {
                            log.debug("Bot batch using saved file: {} ({} bytes)", savedFile, file.length());
                            input = new FileInputStream(file);
                        }
                    }

                    if (input == null) {
                        log.debug("Bot batch envelope file: {}, messageSource: {}",
                                botEnvelope.getFile(), botEnvelope.getMessageSource());
                        if (botEnvelope.getMessageSource() != null) {
                            log.debug("Bot batch messageSource size: {} bytes",
                                    botEnvelope.getMessageSource().size());
                        }
                        input = botEnvelope.openMessageStream();
                    }

                    if (input == null) {
                        log.error("Bot batch could not open message source for session UID: {}",
                                sessionUid);
                        return;
                    }

                    botParser = new EmailParser(input).parse();

                    for (BotInvocation invocation : invocations) {
                        BotDispatch dispatch = invocation.dispatch();
                        Session botSession = batchSession.clone();
                        try {
                            invocation.bot().process(
                                    new Connection(botSession),
                                    botParser,
                                    dispatch.address(),
                                    invocation.definition());
                            succeeded++;
                        } catch (Exception e) {
                            failed++;
                            log.error("Error processing bot {} for address {}: {}",
                                    dispatch.botName(), dispatch.address(), e.getMessage(), e);
                        } finally {
                            botSession.close();
                        }
                    }
                } catch (Exception e) {
                    failed += invocations.size() - succeeded - failed;
                    log.error("Error preparing bot batch for session UID {}: {}",
                            sessionUid, e.getMessage(), e);
                } finally {
                    if (botParser != null) {
                        try {
                            botParser.close();
                        } catch (Exception ignored) {
                        }
                    }
                    if (input != null) {
                        try {
                            input.close();
                        } catch (Exception ignored) {
                        }
                    }
                    batchSession.close();

                    long durationMillis = (System.nanoTime() - startedAt) / 1_000_000;
                    log.info("Completed bot batch for session UID {}: bots={}, succeeded={}, failed={}, durationMs={}",
                            sessionUid,
                            invocations.stream().map(i -> i.dispatch().botName()).toList(),
                            succeeded,
                            failed,
                            durationMillis);
                }
            });
        } catch (RuntimeException e) {
            batchSession.close();
            throw e;
        }
        log.info("Submitted bot batch for session UID {}: {}",
                sessionUid,
                invocations.stream().map(i -> i.dispatch().botName()).toList());
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
