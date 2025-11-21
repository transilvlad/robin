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
                        List.of("example.com"), List.of("127.0.0.1"))
        );
        configMap.put("bots", botsList);

        BotConfig config = new BotConfig(configMap);
        List<BotConfig.BotDefinition> bots = config.getBots();

        assertNotNull(bots);
        assertEquals(1, bots.size());

        BotConfig.BotDefinition bot = bots.get(0);
        assertEquals("^robot@example\\.com$", bot.getAddressPattern());
        assertEquals("session", bot.getBotName());
        assertEquals(1, bot.getDomains().size());
        assertEquals("example.com", bot.getDomains().get(0));
        assertEquals(1, bot.getAllowedIps().size());
        assertEquals("127.0.0.1", bot.getAllowedIps().get(0));
    }

    @Test
    void testBotDefinitionPatternMatching() {
        Map<String, Object> botMap = createBotMap(
                "^robotSession(\\+[^@]+)?@example\\.com$",
                "session",
                List.of("example.com"),
                List.of("127.0.0.1")
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
    void testBotDefinitionDomainCheck() {
        Map<String, Object> botMap = createBotMap(
                "^robot@.*$",
                "session",
                List.of("example.com", "test.com"),
                List.of()
        );

        BotConfig.BotDefinition bot = new BotConfig.BotDefinition(botMap);

        // Test allowed domains
        assertTrue(bot.isDomainAllowed("example.com"));
        assertTrue(bot.isDomainAllowed("test.com"));
        assertTrue(bot.isDomainAllowed("Example.COM")); // Case insensitive

        // Test disallowed domains
        assertFalse(bot.isDomainAllowed("other.com"));
        assertFalse(bot.isDomainAllowed(""));
        assertFalse(bot.isDomainAllowed(null));
    }

    @Test
    void testBotDefinitionNoDomainRestriction() {
        Map<String, Object> botMap = createBotMap(
                "^robot@.*$",
                "session",
                List.of(), // Empty list means all domains allowed
                List.of()
        );

        BotConfig.BotDefinition bot = new BotConfig.BotDefinition(botMap);

        // All domains should be allowed when list is empty
        assertTrue(bot.isDomainAllowed("example.com"));
        assertTrue(bot.isDomainAllowed("any-domain.org"));
        // Note: The implementation allows null/empty when no restrictions
        // This is by design to not block when no restrictions are configured
    }

    @Test
    void testBotDefinitionIpCheck() {
        Map<String, Object> botMap = createBotMap(
                "^robot@.*$",
                "session",
                List.of(),
                List.of("127.0.0.1", "::1", "192.168.1/24")
        );

        BotConfig.BotDefinition bot = new BotConfig.BotDefinition(botMap);

        // Test exact IP matches
        assertTrue(bot.isIpAllowed("127.0.0.1"));
        assertTrue(bot.isIpAllowed("::1"));

        // Test CIDR prefix match (basic implementation using startsWith on the prefix before /)
        // Note: prefix is "192.168.1" so IPs starting with that will match
        assertTrue(bot.isIpAllowed("192.168.1.0"));
        assertTrue(bot.isIpAllowed("192.168.1.100"));
        assertTrue(bot.isIpAllowed("192.168.1.255"));

        // Test disallowed IPs
        assertFalse(bot.isIpAllowed("10.0.0.1"));
        assertFalse(bot.isIpAllowed("192.168.2.1")); // Different subnet
        assertFalse(bot.isIpAllowed(""));
        assertFalse(bot.isIpAllowed(null));
    }

    @Test
    void testBotDefinitionNoIpRestriction() {
        Map<String, Object> botMap = createBotMap(
                "^robot@.*$",
                "session",
                List.of(),
                List.of() // Empty list means all IPs allowed
        );

        BotConfig.BotDefinition bot = new BotConfig.BotDefinition(botMap);

        // All IPs should be allowed when list is empty
        assertTrue(bot.isIpAllowed("127.0.0.1"));
        assertTrue(bot.isIpAllowed("192.168.1.1"));
        assertTrue(bot.isIpAllowed("10.0.0.1"));
        // Note: The implementation allows null/empty when no restrictions
        // This is by design to not block when no restrictions are configured
    }

    @Test
    void testInvalidPatternThrowsException() {
        Map<String, Object> botMap = createBotMap(
                "[invalid(regex",  // Invalid regex
                "session",
                List.of(),
                List.of()
        );

        assertThrows(IllegalArgumentException.class, () -> {
            new BotConfig.BotDefinition(botMap);
        });
    }

    /**
     * Helper method to create a bot configuration map.
     */
    private Map<String, Object> createBotMap(String pattern, String botName, 
                                              List<String> domains, List<String> ips) {
        Map<String, Object> map = new HashMap<>();
        map.put("addressPattern", pattern);
        map.put("botName", botName);
        map.put("domains", domains);
        map.put("allowedIps", ips);
        return map;
    }
}
