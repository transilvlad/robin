package com.mimecast.robin.bots;

import com.mimecast.robin.config.server.BotConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BotConfig.
 */
class BotConfigTest {

    @Test
    void testEmptyConfig() {
        BotConfig config = new BotConfig();
        List<BotConfig.BotDefinition> bots = config.getBots();
        assertNotNull(bots);
        assertTrue(bots.isEmpty());
    }

    @Test
    void testNullMapConfig() {
        BotConfig config = new BotConfig((Map<String, Object>) null);
        List<BotConfig.BotDefinition> bots = config.getBots();
        assertNotNull(bots);
        assertTrue(bots.isEmpty());
    }

    @Test
    void testConfigWithBots() {
        Map<String, Object> configMap = new HashMap<>();
        List<Map<String, Object>> botsList = List.of(
                createBotMap("^robot@example\\.com$", "session",
                        List.of("127.0.0.1"), List.of("token123"))
        );
        configMap.put("bots", botsList);

        BotConfig config = new BotConfig(configMap);
        List<BotConfig.BotDefinition> bots = config.getBots();

        assertNotNull(bots);
        assertEquals(1, bots.size());

        BotConfig.BotDefinition bot = bots.get(0);
        assertEquals("^robot@example\\.com$", bot.getAddressPattern());
        assertEquals("session", bot.getBotName());
        assertEquals(1, bot.getAllowedIps().size());
        assertEquals("127.0.0.1", bot.getAllowedIps().get(0));
        assertEquals(1, bot.getAllowedTokens().size());
        assertEquals("token123", bot.getAllowedTokens().get(0));
    }

    @Test
    void testBotDefinitionPatternMatching() {
        Map<String, Object> botMap = createBotMap(
                "^robotSession(\\+[^@]+)?@example\\.com$",
                "session",
                List.of("127.0.0.1"),
                List.of()
        );

        BotConfig.BotDefinition bot = new BotConfig.BotDefinition(botMap);

        // Test positive matches
        assertTrue(bot.matchesAddress("robotSession@example.com"));
        assertTrue(bot.matchesAddress("robotSession+token@example.com"));
        // Note: Complex sieve pattern would need different regex
        // "robotSession+token+reply+user@domain.com@example.com" doesn't match because of @ in the middle

        // Test negative matches
        assertFalse(bot.matchesAddress("robot@example.com"));
        assertFalse(bot.matchesAddress("robotSession@other.com"));
        assertFalse(bot.matchesAddress(""));
        assertFalse(bot.matchesAddress(null));
    }

    @Test
    void testBotDefinitionAuthorizationByIp() {
        Map<String, Object> botMap = createBotMap(
                "^robot@.*$",
                "session",
                List.of("127.0.0.1", "192.168.1.0/24"),
                List.of() // No tokens
        );

        BotConfig.BotDefinition bot = new BotConfig.BotDefinition(botMap);

        // Test IP authorization
        assertTrue(bot.isAuthorized("robot@example.com", "127.0.0.1"));
        assertTrue(bot.isAuthorized("robot@example.com", "192.168.1.100"));

        // Test unauthorized IP
        assertFalse(bot.isAuthorized("robot@example.com", "10.0.0.1"));
    }

    @Test
    void testBotDefinitionAuthorizationByToken() {
        Map<String, Object> botMap = createBotMap(
                "^robot(\\+[^@]+)?@.*$",
                "session",
                List.of(), // No IPs
                List.of("secret123", "token456")
        );

        BotConfig.BotDefinition bot = new BotConfig.BotDefinition(botMap);

        // Test token authorization
        assertTrue(bot.isAuthorized("robot+secret123@example.com", "10.0.0.1"));
        assertTrue(bot.isAuthorized("robot+token456@example.com", "10.0.0.1"));

        // Test unauthorized token
        assertFalse(bot.isAuthorized("robot+wrongtoken@example.com", "10.0.0.1"));
        assertFalse(bot.isAuthorized("robot@example.com", "10.0.0.1")); // No token
    }

    @Test
    void testBotDefinitionAuthorizationByIpOrToken() {
        Map<String, Object> botMap = createBotMap(
                "^robot(\\+[^@]+)?@.*$",
                "session",
                List.of("127.0.0.1"),
                List.of("secret123")
        );

        BotConfig.BotDefinition bot = new BotConfig.BotDefinition(botMap);

        // Test IP authorization (token not needed)
        assertTrue(bot.isAuthorized("robot@example.com", "127.0.0.1"));

        // Test token authorization (IP not needed)
        assertTrue(bot.isAuthorized("robot+secret123@example.com", "10.0.0.1"));

        // Test both valid
        assertTrue(bot.isAuthorized("robot+secret123@example.com", "127.0.0.1"));

        // Test neither valid
        assertFalse(bot.isAuthorized("robot@example.com", "10.0.0.1"));
        assertFalse(bot.isAuthorized("robot+wrongtoken@example.com", "10.0.0.1"));
    }

    @Test
    void testBotDefinitionNoRestrictions() {
        Map<String, Object> botMap = createBotMap(
                "^robot@.*$",
                "session",
                List.of(), // Empty IPs
                List.of()  // Empty tokens
        );

        BotConfig.BotDefinition bot = new BotConfig.BotDefinition(botMap);

        // All requests should be allowed when both lists are empty
        assertTrue(bot.isAuthorized("robot@example.com", "10.0.0.1"));
        assertTrue(bot.isAuthorized("robot@example.com", "192.168.1.1"));
    }


    @Test
    void testInvalidPatternThrowsException() {
        Map<String, Object> botMap = new HashMap<>();
        botMap.put("addressPattern", "[invalid(regex"); // Invalid regex
        botMap.put("botName", "session");
        botMap.put("allowedIps", List.of());
        botMap.put("allowedTokens", List.of());

        assertThrows(IllegalArgumentException.class, () -> {
            new BotConfig.BotDefinition(botMap);
        });
    }

    /**
     * Helper method to create a bot configuration map.
     */
    private Map<String, Object> createBotMap(String pattern, String botName,
                                              List<String> ips, List<String> tokens) {
        Map<String, Object> map = new HashMap<>();
        map.put("addressPattern", pattern);
        map.put("botName", botName);
        map.put("allowedIps", ips);
        map.put("allowedTokens", tokens);
        return map;
    }
}
