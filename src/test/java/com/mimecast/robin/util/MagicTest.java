package com.mimecast.robin.util;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Execution(ExecutionMode.SAME_THREAD)
@Isolated
class MagicTest {
    private static final String TEST_PROPERTIES_PATH = "src/test/resources/cfg/properties.json5";

    @BeforeAll
    static void before() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void afterEach() throws IOException {
        Config.initProperties(TEST_PROPERTIES_PATH);
    }

    @Test
    void magicReplace() {
        Session session = new Session();

        session.putMagic("port", "25");
        assertEquals("25", Magic.magicReplace("{$port}", session, false));

        session.putMagic("hostnames", List.of("example.com"));
        assertEquals("example.com", Magic.magicReplace("{$hostnames[0]}", session, false));

        session.saveResults("hostnames", List.of("example.com"));
        assertNotNull(Magic.magicReplace("{$hostnames[?]}", session, false));

        session.saveResults("host", List.of(Map.of("com", "example.com")));
        assertEquals("example.com", Magic.magicReplace("{$host[0][com]}", session, false));

        session.putMagic("date", "20240109000000000");
        assertEquals(String.valueOf(1704758400000L), Magic.magicReplace("{dateToMillis$date}", session, false));

        session.putMagic("milis", String.valueOf(1704758400000L));
        assertEquals("20240109000000000", Magic.magicReplace("{millisToDate$milis}", session, false));

        session.putMagic("upper", "ABC");
        assertEquals("abc", Magic.magicReplace("{toLowerCase$upper}", session, false));

        session.putMagic("lower", "def");
        assertEquals("DEF", Magic.magicReplace("{toUpperCase$lower}", session, false));

        session.putMagic("pattern", ".*");
        assertEquals("\\Q.*\\E", Magic.magicReplace("{patternQuote$pattern}", session, false));

        session.putMagic("host", "example.com:8080");
        assertEquals("https://example.com", Magic.magicReplace("https://{strip(:8080)$host}", session, false));

        session.putMagic("host", "example.com:8080");
        assertEquals("https://example.com", Magic.magicReplace("https://{replace(:8080|)$host}", session, false));
    }

    @Test
    void putMagicLoadsConfiguredPropertiesAfterDelayedInit() throws IOException {
        Session beforeInit = new Session();
        assertFalse(beforeInit.hasMagic("magicString"));

        Path propertiesPath = writePropertiesFile("en_US", "first-value", List.of("alpha", "beta"));
        Config.initProperties(propertiesPath.toString());
        assertEquals("first-value", Config.getProperties().getStringProperty("magicString"));
        assertEquals(List.of("alpha", "beta"), Config.getProperties().getListProperty("magicList"));

        Session session = new Session();
        assertEquals("first-value", session.getMagic("magicString"));
        assertEquals(List.of("alpha", "beta"), session.getMagic("magicList"));
        assertEquals("first-value", Magic.magicReplace("{$magicString}", session, false));
    }

    private Path writePropertiesFile(String locale, String stringValue, List<String> listValues) throws IOException {
        Path path = Files.createTempFile("magic-properties-", ".json5");
        String list = listValues.stream()
                .map(value -> "\"" + value + "\"")
                .reduce((left, right) -> left + ", " + right)
                .orElse("");

        Files.writeString(path, "{\n" +
                "  \"locale\": \"" + locale + "\",\n" +
                "  \"magicString\": \"" + stringValue + "\",\n" +
                "  \"magicList\": [" + list + "]\n" +
                "}\n");
        path.toFile().deleteOnExit();
        return path;
    }
}
