package com.mimecast.robin.config.client;

import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestConfigTest {

    @Test
    void inlinePayloadIsMagicReplacedLikeTheUrl() {
        Session session = new Session();
        session.putMagic("queueUid", "abc-123");
        RequestConfig config = new RequestConfig(Map.of(
                "url", "http://localhost/{$queueUid}",
                "content", Map.of("payload", "{\"uid\": \"{$queueUid}\"}", "mimeType", "application/json")), session);

        assertEquals("http://localhost/abc-123", config.getUrl());
        assertEquals("{\"uid\": \"abc-123\"}", config.getContent().getLeft());
        assertEquals("application/json", config.getContent().getRight());
    }
}
