package com.mimecast.robin.smtp;

import com.mimecast.robin.main.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Execution(ExecutionMode.SAME_THREAD)
@Isolated
class MessageEnvelopeTest {
    private static final String TEST_PROPERTIES_PATH = "src/test/resources/cfg/properties.json5";

    @BeforeAll
    static void beforeAll() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void afterEach() throws IOException {
        Config.initProperties(TEST_PROPERTIES_PATH);
    }

    @Test
    void dateUsesCurrentConfiguredLocaleAfterPropertiesReload() throws IOException {
        Path englishProperties = writePropertiesFile("en_US");
        Path japaneseProperties = writePropertiesFile("ja_JP");

        Config.initProperties(englishProperties.toString());
        MessageEnvelope englishEnvelope = new MessageEnvelope();
        assertTrue(englishEnvelope.getDate().contains(currentMonthMarker(Locale.US)));

        Config.initProperties(japaneseProperties.toString());
        MessageEnvelope japaneseEnvelope = new MessageEnvelope();
        assertTrue(japaneseEnvelope.getDate().contains(currentMonthMarker(Locale.JAPAN)));
        assertDoesNotThrow(() -> new SimpleDateFormat("E, d MMM yyyy HH:mm:ss Z", Locale.JAPAN)
                .parse(japaneseEnvelope.getDate()));
    }

    private Path writePropertiesFile(String locale) throws IOException {
        Path path = Files.createTempFile("message-envelope-properties-", ".json5");
        Files.writeString(path, "{\n" +
                "  \"locale\": \"" + locale + "\"\n" +
                "}\n");
        path.toFile().deleteOnExit();
        return path;
    }

    private String currentMonthMarker(Locale locale) {
        return new SimpleDateFormat("MMM", locale).format(new Date());
    }
}
