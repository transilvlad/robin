package com.mimecast.robin.sasl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * A mock subclass of DovecotSaslAuthNative for testing purposes.
 */
public class DovecotSaslAuthNativeMock extends DovecotSaslAuthNative {

    public DovecotSaslAuthNativeMock(String response) {
        super(null);
        this.inputStream = new ByteArrayInputStream((response + "\n").getBytes());
    }

    @Override
    void initSocket() {
        this.outputStream = new ByteArrayOutputStream();
    }
}
