package com.mimecast.robin.storage;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.util.PathUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Local Storage Client.
 * <p>Saves files on disk.
 *
 * @author "Vlad Marian" <vmarian@mimecast.com>
 * @link http://mimecast.com Mimecast
 */
public class LocalStorageClient implements StorageClient {
    private static final Logger log = LogManager.getLogger(LocalStorageClient.class);

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
     * Save file output stream.
     */
    protected OutputStream stream = new NullOutputStream();

    public LocalStorageClient() {
        String now = new SimpleDateFormat("yyyyMMdd", Locale.UK).format(new Date());
        String uid = UUID.randomUUID().toString();

        fileName = now + "." + uid + ".eml";
        path = Config.getServer().getStorageDir();
    }

    /**
     * Sets connection.
     *
     * @param connection Connection instance.
     * @return Self.
     */
    public LocalStorageClient setConnection(Connection connection) {
        this.connection = connection;

        // Append first recipient domain/address to path
        if(connection != null && !connection.getSession().getRcpts().isEmpty()) {
            String[] splits = connection.getSession().getRcpts().get(0).getAddress().split("@");
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
        if (PathUtils.makePath(path)) {
            stream = new FileOutputStream(new File(Paths.get(path, fileName).toString()));

        } else {
            log.error("Storage path could not be created");
        }

        return stream;
    }

    /**
     * Gets file token.
     *
     * @return String.
     */
    @Override
    public String getToken() {
        return Paths.get(path, fileName).toString();
    }

    /**
     * Saves file.
     */
    @Override
    public void save() {
        // TODO Store token in connection session.
        try {
            stream.flush();
            stream.close();
        } catch (IOException e) {
            log.error("Storage file not flushed/closed: {}", e.getMessage());
        }
    }
}
