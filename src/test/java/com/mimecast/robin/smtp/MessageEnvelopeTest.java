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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void setRcpts_replacesLegacySingleRecipient_withoutDuplicate() {
        MessageEnvelope envelope = new MessageEnvelope()
                .setRcpt("pepper@example.com");

        assertEquals(List.of("pepper@example.com"), envelope.getRcpts());

        envelope.setRcpts(List.of("tony@example.com"));

        assertEquals(List.of("tony@example.com"), envelope.getRcpts());
        assertNull(envelope.getRcpt());
    }

    @Test
    void addBotAddress_duplicateAddressAndBotName_recordsOnce() {
        MessageEnvelope envelope = new MessageEnvelope()
                .addBotAddress("robotEmail@example.com", "email")
                .addBotAddress("robotEmail@example.com", "email")
                .addBotAddress("ROBOTEMAIL@example.com", "EMAIL");

        Map<String, List<String>> botAddresses = envelope.getBotAddresses();

        assertEquals(1, botAddresses.size());
        assertEquals(List.of("email"), botAddresses.get("robotEmail@example.com"));
        assertTrue(envelope.isBotAddress("robotemail@example.com"));
    }

    @Test
    void clone_deepCopiesMutableMaps_withoutMutatingOriginal() {
        MessageEnvelope original = new MessageEnvelope()
                .addParam("mail", "SMTPUTF8")
                .addHeader("X-Test", "original")
                .addBotAddress("robotEmail@example.com", "email")
                .addScanResult(Map.of("scanner", "rspamd"));

        MessageEnvelope cloned = original.clone();

        cloned.addParam("rcpt", "NOTIFY=SUCCESS")
                .addHeader("X-Test", "clone")
                .addBotAddress("robotEmail@example.com", "session")
                .addScanResult(Map.of("scanner", "clamav"));

        assertEquals(" SMTPUTF8", original.getParams("mail"));
        assertEquals("", original.getParams("rcpt"));
        assertEquals("original", original.getHeaders().get("X-Test"));
        assertEquals(List.of("email"), original.getBotAddresses().get("robotEmail@example.com"));
        assertEquals(1, original.getScanResults().size());
        assertFalse(original.getBotAddresses().get("robotEmail@example.com").contains("session"));
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
