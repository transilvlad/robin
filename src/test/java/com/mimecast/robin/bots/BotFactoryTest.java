package com.mimecast.robin.bots;

import com.mimecast.robin.main.Factories;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for bot management in Factories.
 */
class BotFactoryTest {

    @Test
    void testSessionBotIsRegistered() {
        assertTrue(Factories.hasBot("session"));
        assertTrue(Factories.hasBot("Session")); // Case insensitive
        assertTrue(Factories.hasBot("SESSION"));
    }

    @Test
    void testGetSessionBot() {
        Optional<BotProcessor> botOpt = Factories.getBot("session");
        assertTrue(botOpt.isPresent());

        BotProcessor bot = botOpt.get();
        assertNotNull(bot);
        assertEquals("session", bot.getName());
        assertInstanceOf(SessionBot.class, bot);
    }

    @Test
    void testGetNonExistentBot() {
        Optional<BotProcessor> botOpt = Factories.getBot("nonexistent");
        assertFalse(botOpt.isPresent());
    }

    @Test
    void testGetBotWithNullName() {
        Optional<BotProcessor> botOpt = Factories.getBot(null);
        assertFalse(botOpt.isPresent());
    }

    @Test
    void testGetBotWithEmptyName() {
        Optional<BotProcessor> botOpt = Factories.getBot("");
        assertFalse(botOpt.isPresent());
    }

    @Test
    void testHasBotWithNullName() {
        assertFalse(Factories.hasBot(null));
    }

    @Test
    void testHasBotWithEmptyName() {
        assertFalse(Factories.hasBot(""));
    }

    @Test
    void testGetBotNames() {
        String[] botNames = Factories.getBotNames();
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
