package com.mimecast.robin.sasl;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("resource")
public class DovecotSaslAuthNativeTest {

    @Test
    void testAuthenticateSuccess() throws IOException {
        DovecotSaslAuthNative authNative = new DovecotSaslAuthNativeMock("OK\t1\tuser=user");
        assertTrue(authNative.authenticate("PLAIN", true, "user", "pass", "smtp", "127.0.0.1", "10.20.0.1"));
    }

    @Test
    void testAuthenticateFailure() throws IOException {
        DovecotSaslAuthNative authNative = new DovecotSaslAuthNativeMock("FAIL\t1\tuser=user\terror=authentication failed");
        assertFalse(authNative.authenticate("PLAIN", false, "user", "pass", "smtp", "127.0.0.1", "10.20.0.1"));
    }

    @Test
    void testValidationSuccess() throws IOException {
        DovecotSaslAuthNative authNative = new DovecotSaslAuthNativeMock("USER\t1");
        assertTrue(authNative.validate("user", "smtp"));
    }

    @Test
    void testValidationFailure() throws IOException {
        DovecotSaslAuthNative authNative = new DovecotSaslAuthNativeMock("NOTFOUND\t1");
        assertFalse(authNative.validate("user", "smtp"));
    }
}