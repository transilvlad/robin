package com.mimecast.robin.queue;

import net.jpountz.lz4.LZ4Factory;
import net.jpountz.xxhash.XXHashFactory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Lz4CompatibilityTest {

    // Produced by net.jpountz.lz4:lz4:1.3.0, formerly supplied by MapDB.
    private static final byte[] LEGACY_BLOCK = Base64.getDecoder()
            .decode("/whsZWdhY3kgbWFwZGIgbHo0IGJsb2NrIBcA/0JQbG9jayA=");
    private static final byte[] PAYLOAD = "legacy mapdb lz4 block ".repeat(16).getBytes(StandardCharsets.UTF_8);

    @Test
    void readsLegacyCompressedBlocks() {
        byte[] restored = new byte[PAYLOAD.length];

        int length = LZ4Factory.safeInstance().safeDecompressor()
                .decompress(LEGACY_BLOCK, 0, LEGACY_BLOCK.length, restored, 0, restored.length);

        assertEquals(PAYLOAD.length, length);
        assertArrayEquals(PAYLOAD, restored);
    }

    @Test
    void preservesLegacyHashValues() {
        assertEquals(2288506272535415453L,
                XXHashFactory.safeInstance().hash64().hash(PAYLOAD, 0, PAYLOAD.length, 0));
        assertEquals(2288506272535415453L,
                XXHashFactory.fastestInstance().hash64().hash(PAYLOAD, 0, PAYLOAD.length, 0));
    }
}
