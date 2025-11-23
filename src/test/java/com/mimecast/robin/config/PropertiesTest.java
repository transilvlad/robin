package com.mimecast.robin.config;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Foundation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;

import static org.junit.jupiter.api.Assertions.*;

class PropertiesTest {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @Test
    void getBooleanProperty() {
        assertTrue(Config.getProperties().hasProperty("boolean"));
        assertTrue(Config.getProperties().getBooleanProperty("boolean"));
    }

    @Test
    void getLongProperty() {
        assertTrue(Config.getProperties().hasProperty("long"));
        assertEquals((Long) 7L, Config.getProperties().getLongProperty("long"));
    }

    @Test
    void getStringProperty() {
        assertTrue(Config.getProperties().hasProperty("string"));
        assertEquals("string", Config.getProperties().getStringProperty("string"));
    }

    @Test
    void getStringSubProperty() {
        assertEquals("substring", Config.getProperties().getStringProperty("sub.string"));
    }

    @Test
    void getListProperty() {
        assertTrue(Config.getProperties().hasProperty("list"));
        assertEquals("[monkey, weasel, dragon]", Config.getProperties().getListProperty("list").toString());
    }

    @Test
    void getMapProperty() {
        assertTrue(Config.getProperties().hasProperty("map"));
        assertEquals(1, Config.getProperties().getMapProperty("map").size());
        assertEquals("map", Config.getProperties().getMapProperty("map").get("string"));
    }

    @Test
    void getPropertyWithDefault() {
        assertFalse(Config.getProperties().hasProperty("default"));
        assertEquals("value", Config.getProperties().getStringProperty("default", "value"));
    }

    @Test
    void getLocalLogsDir() {
        assertTrue(Config.getProperties().hasProperty("localLogsDir"));
        assertEquals("/usr/local/robin/log/", Config.getProperties().getStringProperty("localLogsDir"));
    }

    @Test
    void getUidPattern() {
        assertTrue(Config.getProperties().hasProperty("uidPattern"));
        assertEquals("\\s\\[([a-z0-9\\-_]+)]", Config.getProperties().getStringProperty("uidPattern"));
    }

    @Test
    void getRsetBetweenEnvelopes() {
        assertTrue(Config.getProperties().hasProperty("rsetBetweenEnvelopes"));
        assertFalse(Config.getProperties().getBooleanProperty("rsetBetweenEnvelopes"));
    }

    @Test
    void getPropertiesAutoReload() {
        assertTrue(Config.getProperties().hasProperty("propertiesAutoReload"));
        var autoReload = Config.getProperties().getMapProperty("propertiesAutoReload");
        assertNotNull(autoReload);
        assertEquals(false, autoReload.get("enabled"));
        assertEquals(300.0, autoReload.get("delaySeconds"));
        assertEquals(300.0, autoReload.get("intervalSeconds"));
    }

    @Test
    void getServerAutoReload() {
        assertTrue(Config.getProperties().hasProperty("serverAutoReload"));
        var autoReload = Config.getProperties().getMapProperty("serverAutoReload");
        assertNotNull(autoReload);
        assertEquals(false, autoReload.get("enabled"));
        assertEquals(300.0, autoReload.get("delaySeconds"));
        assertEquals(300.0, autoReload.get("intervalSeconds"));
    }

    @Test
    void getLoggingConfig() {
        assertTrue(Config.getProperties().hasProperty("logging"));
        var logging = Config.getProperties().getMapProperty("logging");
        assertNotNull(logging);
        assertEquals(false, logging.get("data"));
        assertEquals(false, logging.get("textPartBody"));
    }

    @Test
    void getRequestConfig() {
        assertTrue(Config.getProperties().hasProperty("request"));
        var request = Config.getProperties().getMapProperty("request");
        assertNotNull(request);
        assertEquals(20.0, request.get("connectTimeout"));
        assertEquals(20.0, request.get("writeTimeout"));
        assertEquals(90.0, request.get("readTimeout"));
    }

    @Test
    void getHumioConfig() {
        assertTrue(Config.getProperties().hasProperty("humio"));
        var humio = Config.getProperties().getMapProperty("humio");
        assertNotNull(humio);
        assertEquals("YOUR_API_KEY", humio.get("auth"));
        assertEquals("https://humio.example.com/", humio.get("url"));
        assertEquals(20.0, humio.get("connectTimeout"));
        assertEquals(20.0, humio.get("writeTimeout"));
        assertEquals(90.0, humio.get("readTimeout"));
    }

    @Test
    void getImapClientDebug() {
        assertTrue(Config.getProperties().hasProperty("imapClientDebug"));
        assertEquals("false", Config.getProperties().getStringProperty("imapClientDebug"));
    }

    @Test
    void getDigestMd5Random() {
        assertTrue(Config.getProperties().hasProperty("digestmd5.random"));
        assertEquals("64", Config.getProperties().getStringProperty("digestmd5.random"));
    }
}
