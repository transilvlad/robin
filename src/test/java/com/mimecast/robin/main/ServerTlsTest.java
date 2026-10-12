package com.mimecast.robin.main;

import com.mimecast.robin.config.server.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ServerTlsTest {
    @TempDir
    Path dir;

    private final Map<String, String> previousProperties = new java.util.HashMap<>();

    @BeforeEach
    void saveTlsProperties() {
        for (String name : new String[]{"javax.net.ssl.keyStore", "javax.net.ssl.keyStorePassword", "javax.net.ssl.keyStoreType"}) {
            previousProperties.put(name, System.getProperty(name));
        }
    }

    @AfterEach
    void restoreTlsProperties() throws Exception {
        String current = System.getProperty("javax.net.ssl.keyStore");
        if (current != null && Path.of(current).getFileName().toString().startsWith("robin-ks-")) {
            Files.deleteIfExists(Path.of(current));
        }
        previousProperties.forEach(ServerTlsTest::restore);
    }

    @Test
    void noTlsDoesNotRequireKeystore() {
        assertDoesNotThrow(() -> Server.loadKeystore(new ServerConfig(
                Map.of("starttls", false, "securePort", 0))));
    }

    @Test
    void partialPemAndMissingOrInvalidKeyStoresFailBeforeListeners() throws Exception {
        assertThrows(IllegalStateException.class, () -> Server.loadKeystore(
                new ServerConfig(Map.of("pemCert", "missing.pem"))));
        assertThrows(IllegalStateException.class, () -> Server.loadKeystore(
                new ServerConfig(Map.of("keystore", dir.resolve("missing").toString()))));
        Path invalid = dir.resolve("invalid.jks");
        Files.writeString(invalid, "not a keystore");
        assertThrows(IllegalStateException.class, () -> Server.loadKeystore(
                new ServerConfig(Map.of("keystore", invalid.toString(), "keystorepassword", "test"))));
        Path empty = dir.resolve("empty.p12");
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, "test".toCharArray());
        try (var output = Files.newOutputStream(empty)) {
            store.store(output, "test".toCharArray());
        }
        assertThrows(IllegalStateException.class, () -> Server.loadKeystore(
                new ServerConfig(Map.of("keystore", empty.toString(), "keystorepassword", "test"))));
    }

    @Test
    void invalidPemDoesNotFallBackToDemoKeystore() throws Exception {
        Path cert = dir.resolve("cert.pem");
        Path key = dir.resolve("key.pem");
        Files.writeString(cert, "invalid certificate");
        Files.writeString(key, "invalid key");
        assertThrows(IllegalStateException.class, () -> Server.loadKeystore(new ServerConfig(Map.of(
                "pemCert", cert.toString(), "pemKey", key.toString(),
                "keystore", "src/test/resources/keystore.jks", "keystorepassword", "avengers"))));
    }

    @Test
    void existingTestKeystoreRemainsSupported() {
        assertDoesNotThrow(() -> Server.loadKeystore(new ServerConfig(Map.of(
                "keystore", "src/test/resources/keystore.jks", "keystorepassword", "avengers"))));
        assertEquals("src/test/resources/keystore.jks", System.getProperty("javax.net.ssl.keyStore"));
    }

    @Test
    void validRsaAndEcPemLoadWithoutConfiguredPassword() throws Exception {
        for (String algorithm : new String[]{"RSA", "EC"}) {
            Path[] pem = createPem(algorithm, algorithm, "-1d", 30);
            Server.loadKeystore(pemConfig(pem[0], pem[1]));
            assertEquals("PKCS12", System.getProperty("javax.net.ssl.keyStoreType"));
            KeyStore loaded = KeyStore.getInstance("PKCS12");
            Path generated = Path.of(System.getProperty("javax.net.ssl.keyStore"));
            try (var input = Files.newInputStream(generated)) {
                loaded.load(input, System.getProperty("javax.net.ssl.keyStorePassword").toCharArray());
            }
            assertTrue(loaded.isKeyEntry("robin"));
            assertEquals(algorithm, loaded.getCertificate("robin").getPublicKey().getAlgorithm());
            Files.delete(generated);
        }
    }

    @Test
    void mismatchedAndExpiredPemFailBeforeListeners() throws Exception {
        Path[] first = createPem("first", "RSA", "-1d", 30);
        Path[] second = createPem("second", "RSA", "-1d", 30);
        assertThrows(IllegalStateException.class, () -> Server.loadKeystore(pemConfig(first[0], second[1])));
        Path[] expired = createPem("expired", "RSA", "-3d", 1);
        assertThrows(IllegalStateException.class, () -> Server.loadKeystore(pemConfig(expired[0], expired[1])));
    }

    private ServerConfig pemConfig(Path cert, Path key) {
        return new ServerConfig(Map.of("pemCert", cert.toString(), "pemKey", key.toString()));
    }

    private Path[] createPem(String name, String algorithm, String startDate, int validity) throws Exception {
        Path storePath = dir.resolve(name + ".p12");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", executable);
        Path output = dir.resolve(name + ".log");
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "test",
                "-keyalg", algorithm, "-dname", "CN=mail.example.test", "-startdate", startDate,
                "-validity", Integer.toString(validity), "-storetype", "PKCS12",
                "-keystore", storePath.toString(), "-storepass", "test-only-password")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Certificate generation timed out");
        }
        assertEquals(0, process.exitValue(), Files.readString(output));
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(storePath)) {
            store.load(input, "test-only-password".toCharArray());
        }
        Path cert = dir.resolve(name + ".crt");
        Path key = dir.resolve(name + ".key");
        writePem(cert, "CERTIFICATE", store.getCertificate("test").getEncoded());
        writePem(key, "PRIVATE KEY", store.getKey("test", "test-only-password".toCharArray()).getEncoded());
        return new Path[]{cert, key};
    }

    private void writePem(Path path, String type, byte[] content) throws Exception {
        Files.writeString(path, "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(content)
                + "\n-----END " + type + "-----\n");
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
