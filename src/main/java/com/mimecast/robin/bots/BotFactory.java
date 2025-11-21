package com.mimecast.robin.bots;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Factory for creating and managing bot processor instances.
 * <p>Bots must be registered with the factory before they can be used.
 * <p>This class uses a singleton pattern and is thread-safe.
 */
public class BotFactory {
    private static final Logger log = LogManager.getLogger(BotFactory.class);

    /**
     * Map of bot name to bot processor instance.
     */
    private static final Map<String, BotProcessor> bots = new HashMap<>();

    /**
     * Static initializer to register all available bots.
     */
    static {
        registerBot(new SessionBot());
    }

    /**
     * Private constructor to prevent instantiation.
     */
    private BotFactory() {
        throw new IllegalStateException("Static factory class");
    }

    /**
     * Registers a bot processor with the factory.
     *
     * @param bot Bot processor to register.
     */
    public static synchronized void registerBot(BotProcessor bot) {
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
}
