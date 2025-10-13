package com.mimecast.robin.sasl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.UnixDomainSocketAddress;

/**
 * A mock subclass of DovecotSaslAuthNative for testing purposes.
 */
public class DovecotSaslAuthNativeMock extends DovecotSaslAuthNative {

    public DovecotSaslAuthNativeMock(String response) {
        super();
        this.inputStream = new ByteArrayInputStream((response + "\n").getBytes());
    }

    @Override
    void initSocket(UnixDomainSocketAddress address) {
        this.outputStream = new ByteArrayOutputStream();
    }
}
