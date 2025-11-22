package com.mimecast.robin.main;

import com.mimecast.robin.annotation.Plugin;
import com.mimecast.robin.assertion.client.ExternalClient;
import com.mimecast.robin.assertion.client.imap.ImapExternalClient;
import com.mimecast.robin.assertion.client.logs.LogsExternalClient;
import com.mimecast.robin.bots.BotProcessor;
import com.mimecast.robin.bots.SessionBot;
import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.queue.QueueDatabase;
import com.mimecast.robin.queue.QueueFactory;
import com.mimecast.robin.queue.RelaySession;
import com.mimecast.robin.smtp.auth.DigestCache;
import com.mimecast.robin.smtp.auth.StaticDigestCache;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.extension.client.Behaviour;
import com.mimecast.robin.smtp.extension.client.DefaultBehaviour;
import com.mimecast.robin.smtp.security.DefaultTLSSocket;
import com.mimecast.robin.smtp.security.TLSSocket;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.storage.*;
import com.mimecast.robin.trust.PermissiveTrustManager;
import com.mimecast.robin.trust.TrustManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factories for pluggable components.
 *
 * <p>This is a factories container for extensible components.
 * <p>You may write a plugin to inject yours.
 *
 * @see Plugin
 */
public class Factories {
    private static final Logger log = LogManager.getLogger(Factories.class);

    /**
     * SMTP client behaviour.
     * <p>The logic of the client.
     */
    private static Callable<Behaviour> behaviour;

    /**
     * Session.
     * <p>Used by both client and server.
     */
    private static Callable<Session> session;

    /**
     * TLS socket implementation.
     * <p>Implements TLS handshake.
     */
    private static Callable<TLSSocket> tlsSocket;

    /**
     * Trust manager implementation.
     * <p>Implements javax.net.ssl.X509TrustManager.
     */
    private static Callable<X509TrustManager> trustManager;

    /**
     * Digest MD5 database.
     * <p>Only used for subsequent authentication.
     */
    private static Callable<DigestCache> database;

    /**
     * Queue database implementation.
     * <p>Used for persistent queue storage.
     */
    private static Callable<QueueDatabase<RelaySession>> queueDatabase;

    /**
     * MTA storage client.
     * <p>Used to store MTA emails received.
     */
    private static Callable<StorageClient> storageClient;

    /**
     * MTA storage processors.
     * <p>Used to do post receipt processing like virus and spam scanning or dovecot LDA delivery.
     */
    private static final List<Callable<StorageProcessor>> storageProcessors = List.of(
            SpamStorageProcessor::new,
            AVStorageProcessor::new,
            LocalStorageProcessor::new,
            DovecotStorageProcessor::new
    );

    /**
     * External clients.
     * <p>Used to fetch external service logs for assertion.
     */
    private static final Map<String, Callable<ExternalClient>> externalClients = new HashMap<>() {{
        put("logs", LogsExternalClient::new);
        put("imap", ImapExternalClient::new);
    }};

    /**
     * Bot processors.
     * <p>Map of bot name to bot processor instance.
     * <p>Using ConcurrentHashMap for thread-safe read/write operations.
     */
    private static final Map<String, BotProcessor> bots = new ConcurrentHashMap<>();

    /**
     * Static initializer to register all available bots.
     */
    static {
        registerBot(new SessionBot());
    }

    /**
     * Protected constructor.
     */
    private Factories() {
        throw new IllegalStateException("Static class");
    }

    /**
     * Sets Behaviour.
     *
     * @param callable Behaviour callable.
     */
    public static void setBehaviour(Callable<Behaviour> callable) {
        behaviour = callable;
    }

    /**
     * Gets Behaviour.
     *
     * @return Behaviour instance.
     */
    public static Behaviour getBehaviour() {
        if (behaviour != null) {
            try {
                return behaviour.call();
            } catch (Exception e) {
                log.error("Error calling behaviour: {}", e.getMessage());
            }
        }

        return new DefaultBehaviour();
    }

    /**
     * Sets Session.
     *
     * @param callable Session callable.
     */
    public static void setSession(Callable<Session> callable) {
        session = callable;
    }

    /**
     * Gets Session.
     *
     * @return Session instance.
     */
    public static Session getSession() {
        if (session != null) {
            try {
                return session.call();
            } catch (Exception e) {
                log.error("Error calling session: {}", e.getMessage());
            }
        }

        return new Session();
    }

    /**
     * Sets TLSSocket.
     *
     * @param callable TLSSocket callable.
     */
    public static void setTLSSocket(Callable<TLSSocket> callable) {
        tlsSocket = callable;
    }

    /**
     * Gets TLSSocket.
     *
     * @return TLSSocket instance.
     */
    public static TLSSocket getTLSSocket() {
        if (tlsSocket != null) {
            try {
                return tlsSocket.call();
            } catch (Exception e) {
                log.error("Error calling TLS socket: {}", e.getMessage());
            }
        }

        return new DefaultTLSSocket();
    }

    /**
     * Sets TrustManager.
     *
     * @param callable TrustManager callable.
     */
    public static void setTrustManager(Callable<X509TrustManager> callable) {
        trustManager = callable;
    }

    /**
     * Gets TrustManager.
     *
     * @return TrustManager instance.
     * @throws Exception If the TrustManager cannot be created.
     */
    public static X509TrustManager getTrustManager() throws Exception {
        if (trustManager != null) {
            try {
                return trustManager.call();
            } catch (Exception e) {
                log.error("Error calling trust manager: {}", e.getMessage());
            }
        }

        // Use permissive trust manager if allowSelfSigned is enabled.
        if (Config.getServer().isAllowSelfSigned()) {
            log.warn("PermissiveTrustManager enabled: accepting self-signed certificates");
            return new PermissiveTrustManager();
        }

        return new TrustManager();
    }

    /**
     * Sets DigestDatabase.
     *
     * @param callable DigestDatabase callable.
     */
    public static void setDatabase(Callable<DigestCache> callable) {
        database = callable;
    }

    /**
     * Gets DigestDatabase.
     *
     * @return DigestDatabase instance.
     */
    public static DigestCache getDatabase() {
        if (database != null) {
            try {
                return database.call();
            } catch (Exception e) {
                log.error("Error calling database: {}", e.getMessage());
            }
        }

        return new StaticDigestCache();
    }

    /**
     * Sets StorageClient.
     *
     * @param callable StorageClient callable.
     */
    public static void setStorageClient(Callable<StorageClient> callable) {
        storageClient = callable;
    }

    /**
     * Gets StorageClient.
     *
     * @param extension  File extension.
     * @param connection Connection instance.
     * @return StorageClient instance.
     */
    public static StorageClient getStorageClient(Connection connection, String extension) {
        if (storageClient != null) {
            try {
                return storageClient.call().setConnection(connection).setExtension(extension);
            } catch (Exception e) {
                log.error("Error calling storage client: {}", e.getMessage());
            }
        }

        return new LocalStorageClient().setConnection(connection).setExtension(extension);
    }

    /**
     * Gets StorageProcessors.
     *
     * @return list of Callable<StorageProcessor>.
     */
    public static List<Callable<StorageProcessor>> getStorageProcessors() {
        return storageProcessors;
    }

    /**
     * Adds StorageProcessor.
     *
     * @param storageProcessor StorageProcessor callable.
     */
    public static void addStorageProcessor(Callable<StorageProcessor> storageProcessor) {
        Factories.storageProcessors.add(storageProcessor);
    }

    /**
     * Puts ExternalClient.
     *
     * @param key      Config map key.
     * @param callable ExternalClient callable.
     */
    public static void putExternalClient(String key, Callable<ExternalClient> callable) {
        externalClients.put(key, callable);
    }

    /**
     * Gets ExternalClient by key.
     *
     * @param key        Config map key.
     * @param connection Connection instance.
     * @param config     BasicConfig instance.
     * @return ExternalClient instance.
     */
    public static ExternalClient getExternalClient(String key, Connection connection, BasicConfig config) {
        if (externalClients.get(key) != null) {
            try {
                return externalClients.get(key).call().setConnection(connection)
                        .setConfig(config);
            } catch (Exception e) {
                log.error("Error calling external client: {}", e.getMessage());
            }
        }

        return null;
    }

    /**
     * Gets ExternalClient keys.
     *
     * @return list of String.
     */
    public static List<String> getExternalKeys() {
        return new ArrayList<>(externalClients.keySet());
    }

    /**
     * Registers a bot processor.
     * <p>Thread-safe thanks to ConcurrentHashMap.
     *
     * @param bot Bot processor to register.
     */
    public static void registerBot(BotProcessor bot) {
        if (bot != null && bot.getName() != null && !bot.getName().isEmpty()) {
            bots.put(bot.getName().toLowerCase(), bot);
            log.info("Registered bot: {}", bot.getName());
        } else {
            log.warn("Attempted to register invalid bot (null or empty name)");
        }
    }

    /**
     * Gets a bot processor by name.
     *
     * @param name Bot name (case-insensitive).
     * @return Optional containing the bot processor if found.
     */
    public static Optional<BotProcessor> getBot(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(bots.get(name.toLowerCase()));
    }

    /**
     * Checks if a bot is registered.
     *
     * @param name Bot name (case-insensitive).
     * @return true if bot is registered.
     */
    public static boolean hasBot(String name) {
        return name != null && !name.isEmpty() && bots.containsKey(name.toLowerCase());
    }

    /**
     * Gets all registered bot names.
     *
     * @return Array of bot names.
     */
    public static String[] getBotNames() {
        return bots.keySet().toArray(new String[0]);
    }

    /**
     * Sets QueueDatabase callable for custom factory override.
     * <p>Used primarily in tests to inject custom queue implementations.
     *
     * @param callable QueueDatabase callable
     */
    public static void setQueueDatabase(Callable<QueueDatabase<RelaySession>> callable) {
        queueDatabase = callable;
    }

    /**
     * Gets QueueDatabase instance using configuration-based backend selection.
     * <p>If a custom factory has been set via {@link #setQueueDatabase(Callable)}, uses that.
     * Otherwise delegates to {@link QueueFactory#createQueueDatabase()} which selects backend
     * based on configuration priority: MapDB → MariaDB → PostgreSQL → InMemory.
     *
     * @return QueueDatabase instance
     */
    public static QueueDatabase<RelaySession> getQueueDatabase() {
        if (queueDatabase != null) {
            try {
                return queueDatabase.call();
            } catch (Exception e) {
                log.error("Error calling queue database: {}", e.getMessage());
            }
        }

        // Use factory to select appropriate backend based on configuration
        return QueueFactory.createQueueDatabase();
    }
}
