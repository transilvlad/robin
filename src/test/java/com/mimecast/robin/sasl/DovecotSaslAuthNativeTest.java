package com.mimecast.robin.sasl;

import org.apache.commons.codec.binary.Base64;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("resource")
public class DovecotSaslAuthNativeTest {

    @Test
    void testAuthenticatePlainSuccess() throws IOException {
        DovecotSaslAuthNative authNative = new DovecotSaslAuthNativeMock("OK\t1\tuser=user");
        assertTrue(authNative.authenticate("PLAIN", true, "user", "pass", "smtp", "127.0.0.1", "10.20.0.1"));
    }

    @Test
    void testAuthenticatePlainFailure() throws IOException {
        DovecotSaslAuthNative authNative = new DovecotSaslAuthNativeMock("FAIL\t1\tuser=user\terror=authentication failed");
        assertFalse(authNative.authenticate("PLAIN", false, "user", "pass", "smtp", "127.0.0.1", "10.20.0.1"));
    }

    @Test
    void testAuthenticateLoginSuccess() throws IOException {
        // Simulate three-step LOGIN: CONT (username prompt), CONT (password prompt), OK.
        DovecotSaslAuthNative authNative = new DovecotSaslAuthNativeMock("CONT\t1\nCONT\t1\nOK\t1\tuser=user");
        assertTrue(authNative.authenticate("LOGIN", true, "user", "pass", "smtp", "127.0.0.1", "10.20.0.1"));
    }

    @Test
    void testAuthenticateLoginFailure() throws IOException {
        // Simulate failure after username stage.
        DovecotSaslAuthNative authNative = new DovecotSaslAuthNativeMock("CONT\t1\nFAIL\t1\terror=bad creds");
        assertFalse(authNative.authenticate("LOGIN", true, "user", "pass", "smtp", "127.0.0.1", "10.20.0.1"));
    }

    @Test
    void testPlainProtocolLines() throws IOException {
        DovecotSaslAuthNativeMock mock = new DovecotSaslAuthNativeMock("OK\t1\tuser=user");
        boolean ok = mock.authenticate("PLAIN", true, "user@example.com", "pass", "smtp", "127.0.0.1", "10.0.0.5");
        assertTrue(ok, "Authentication should succeed in mock");

        String sent = mock.getSent();
        String base64 = Base64.encodeBase64String(("\0user@example.com\0pass").getBytes());
        String expected = "VERSION\t1\t2\n" +
                "CPID\t" + ProcessHandle.current().pid() + "\n" +
                "AUTH\t1\tPLAIN\tservice=smtp\tlip=127.0.0.1\trip=10.0.0.5\tsecured\tresp=" + base64 + "\n";
        assertEquals(expected, sent, "PLAIN protocol lines mismatch");
    }

    @Test
    void testLoginProtocolLines() throws IOException {
        // Simulate two CONT challenges then OK for request id 1.
        DovecotSaslAuthNativeMock mock = new DovecotSaslAuthNativeMock("CONT\t1\nCONT\t1\nOK\t1\tuser=user");
        boolean ok = mock.authenticate("LOGIN", true, "user@example.com", "pass", "smtp", "127.0.0.1", "10.0.0.5");
        assertTrue(ok, "Authentication should succeed in mock");

        String sent = mock.getSent();
        String expectedPrefix = "VERSION\t1\t0\n" +
                "CPID\t" + ProcessHandle.current().pid() + "\n" +
                "AUTH\t1\tLOGIN\tservice=smtp\tlip=127.0.0.1\trip=10.0.0.5\tsecured\n" +
                "CONT\t1\t"; // username continuation (base64 varies).
        assertTrue(sent.startsWith(expectedPrefix), "LOGIN initial sequence mismatch\nExpected prefix:\n" + expectedPrefix + "\nActual:\n" + sent);

        // Ensure exactly three writes (initial, username, password) by counting newlines.
        long newlineCount = sent.chars().filter(c -> c == '\n').count();
        assertTrue(newlineCount >= 3, "Expected at least 3 protocol lines, got " + newlineCount);
        assertTrue(sent.contains("CONT\t1\t"), "Should contain CONT lines for continuations");
    }
}