package com.mimecast.robin.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigSourceFingerprintTest {
    @Test
    void canonicalHashIgnoresObjectOrderWhitespaceAndCommentsButPreservesArrayOrder() {
        assertEquals(ConfigSourceFingerprint.sha256("{\"a\":\"<tag>\",\"z\":{\"a\":1,\"b\":2}}"),
                ConfigSourceFingerprint.sha256("{/*source*/z:{b:2,a:1},a:'<tag>'}"));
        assertNotEquals(ConfigSourceFingerprint.sha256("{a:[1,2]}"),
                ConfigSourceFingerprint.sha256("{a:[2,1]}"));
    }

    @Test
    void fingerprintsSecretReferenceRatherThanExpandedValue() {
        assertNotEquals(ConfigSourceFingerprint.sha256("{password:'{$secret:SYNTHETIC}'}"),
                ConfigSourceFingerprint.sha256("{password:'synthetic-value'}"));
    }
}
