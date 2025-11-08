package com.mimecast.robin.storage;

import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.queue.relay.RelayMessage;
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
import java.util.Optional;
import java.util.concurrent.Callable;

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
            String[] splits;
            if (connection.getSession().isInbound()) {
                splits = connection.getSession().getEnvelopes().getLast().getRcpts().getFirst().split("@");
            } else {
                splits = connection.getSession().getEnvelopes().getLast().getMail().split("@");
            }
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
        String folder = config.getStorage().getStringProperty(connection.getSession().isInbound() ? "inboundFolder" : "outboundFolder");
        return folder != null ? Paths.get(path, folder).toString() : path;
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
                parser = new EmailParser(getFile()).parse();

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
                        if (!storageProcessor.call().process(connection, parser)) {
                            return false;
                        }
                    } catch (Exception e) {
                        log.error("Storage processor error: {}", e.getMessage());
                        return false;
                    }
                }

                // Relay email if X-Robin-Relay or relay configuration or direction outbound enabled.
                relay();
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
     * Relay email to another server by header or config.
     * <p>Will relay email to provided server.
     */
    private void relay() {
        if (!connection.getSession().getEnvelopes().isEmpty()) {
            new RelayMessage(connection, parser).relay();
        }
    }
}
