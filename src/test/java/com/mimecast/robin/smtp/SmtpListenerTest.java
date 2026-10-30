package com.mimecast.robin.smtp;

import com.mimecast.robin.config.ConfigLoader;
import com.mimecast.robin.config.server.ListenerConfig;
import com.mimecast.robin.main.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class SmtpListenerTest {
    @TempDir
    Path directory;

    @Test
    void saturatedWorkerDoesNotRunSessionOnAcceptorAndListenerRecovers() throws Exception {
        Files.writeString(directory.resolve("server.json5"),
                "{hostname:'fixture.test',rbl:{enabled:false},blocklist:{enabled:false},geoip:{enabled:false}}");
        Config.initServer(directory.resolve("server.json5").toString());
        var config = new ListenerConfig(Map.of("minimumPoolSize", 1L, "maximumPoolSize", 1L,
                "dosProtectionEnabled", false));
        var listener = new SmtpListener(0, "127.0.0.1", config, false, false);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var serving = executor.submit(listener::listen);
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (listener.getListener() == null) {
                        Thread.sleep(10);
                    }
                });
                int port = listener.getListener().getLocalPort();
                try (Socket occupied = new Socket("127.0.0.1", port)) {
                    occupied.setSoTimeout(3000);
                    assertTrue(new BufferedReader(new InputStreamReader(occupied.getInputStream(),
                            StandardCharsets.US_ASCII)).readLine().startsWith("220"));
                    try (Socket rejected = new Socket("127.0.0.1", port)) {
                        rejected.setSoTimeout(3000);
                        assertEquals(-1, rejected.getInputStream().read());
                    }
                    assertEquals(1, listener.getActiveThreads());
                }
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (listener.getActiveThreads() != 0) {
                        Thread.sleep(10);
                    }
                });
                try (Socket recovered = new Socket("127.0.0.1", port)) {
                    recovered.setSoTimeout(3000);
                    assertTrue(new BufferedReader(new InputStreamReader(recovered.getInputStream(),
                            StandardCharsets.US_ASCII)).readLine().startsWith("220"));
                }
            } finally {
                listener.serverShutdown();
                serving.get();
            }
        } finally {
            ConfigLoader.load("src/test/resources/cfg/");
        }
    }
}
