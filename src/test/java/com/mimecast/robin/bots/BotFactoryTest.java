package com.mimecast.robin.bots;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BotFactory.
 */
class BotFactoryTest {

    @Test
    void testSessionBotIsRegistered() {
        assertTrue(BotFactory.hasBot("session"));
        assertTrue(BotFactory.hasBot("Session")); // Case insensitive
        assertTrue(BotFactory.hasBot("SESSION"));
    }

    @Test
    void testGetSessionBot() {
        Optional<BotProcessor> botOpt = BotFactory.getBot("session");
        assertTrue(botOpt.isPresent());

        BotProcessor bot = botOpt.get();
        assertNotNull(bot);
        assertEquals("session", bot.getName());
        assertInstanceOf(SessionBot.class, bot);
    }

    @Test
    void testGetNonExistentBot() {
        Optional<BotProcessor> botOpt = BotFactory.getBot("nonexistent");
        assertFalse(botOpt.isPresent());
    }

    @Test
    void testGetBotWithNullName() {
        Optional<BotProcessor> botOpt = BotFactory.getBot(null);
        assertFalse(botOpt.isPresent());
    }

    @Test
    void testGetBotWithEmptyName() {
        Optional<BotProcessor> botOpt = BotFactory.getBot("");
        assertFalse(botOpt.isPresent());
    }

    @Test
    void testHasBotWithNullName() {
        assertFalse(BotFactory.hasBot(null));
    }

    @Test
    void testHasBotWithEmptyName() {
        assertFalse(BotFactory.hasBot(""));
    }

    @Test
    void testGetBotNames() {
        String[] botNames = BotFactory.getBotNames();
        assertNotNull(botNames);
        assertTrue(botNames.length > 0);

        // Session bot should be registered
        boolean hasSession = false;
        for (String name : botNames) {
            if ("session".equals(name)) {
                hasSession = true;
                break;
            }
        }
        assertTrue(hasSession, "Session bot should be in registered bot names");
    }
}
