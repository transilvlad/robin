package com.mimecast.robin.config.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the JVM property override of the per-listener validation setting.
 *
 * <p>{@code @Isolated} + same-thread execution, like {@code MagicTest}: several tests here
 * mutate the shared {@code mta.skipfromvalidation} system property, which this project's JUnit
 * config runs concurrently by default.
 */
@Execution(ExecutionMode.SAME_THREAD)
@Isolated
class ListenerConfigTest {
    private String previousOverride;

    @BeforeEach
    void clearOverride() {
        previousOverride = System.getProperty("mta.skipfromvalidation");
        System.clearProperty("mta.skipfromvalidation");
    }

    @AfterEach
    void restoreOverride() {
        if (previousOverride == null) {
            System.clearProperty("mta.skipfromvalidation");
        } else {
            System.setProperty("mta.skipfromvalidation", previousOverride);
        }
    }

    @Test
    void defaultsToFalseWhenNeitherIsSet() {
        ListenerConfig config = new ListenerConfig();

        assertFalse(config.isSkipFromValidation());
    }

    @Test
    void usesStaticJson5ValueWhenNoOverridePresent() {
        ListenerConfig config = new ListenerConfig(Map.of("skipFromValidation", true));

        assertTrue(config.isSkipFromValidation());
    }

    @Test
    void systemPropertyOverridesStaticTrue() {
        System.setProperty("mta.skipfromvalidation", "false");
        ListenerConfig config = new ListenerConfig(Map.of("skipFromValidation", true));

        assertFalse(config.isSkipFromValidation());
    }

    @Test
    void systemPropertyOverridesStaticFalse() {
        System.setProperty("mta.skipfromvalidation", "true");
        ListenerConfig config = new ListenerConfig(Map.of("skipFromValidation", false));

        assertTrue(config.isSkipFromValidation());
    }

    @Test
    void blankSystemPropertyLeavesStaticDefaultUntouched() {
        System.setProperty("mta.skipfromvalidation", "");
        ListenerConfig config = new ListenerConfig(Map.of("skipFromValidation", true));

        assertTrue(config.isSkipFromValidation());
    }

    @Test
    void whitespaceSystemPropertyLeavesStaticDefaultUntouched() {
        System.setProperty("mta.skipfromvalidation", "   ");

        assertTrue(new ListenerConfig(Map.of("skipFromValidation", true)).isSkipFromValidation());
    }
}
