package com.mimecast.robin.sasl;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DovecotUserLookupNativeTest {
    @Test void testValidateSuccess() throws IOException { try (DovecotUserLookupNativeMock mock = new DovecotUserLookupNativeMock("USER\t1")) { assertTrue(mock.validate("user","smtp")); } }
    @Test void testValidateFailure() throws IOException { try (DovecotUserLookupNativeMock mock = new DovecotUserLookupNativeMock("NOTFOUND\t1")) { assertFalse(mock.validate("user","smtp")); } }
}
