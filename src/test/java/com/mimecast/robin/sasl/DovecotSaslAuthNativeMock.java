package com.mimecast.robin.sasl;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * A mock subclass of DovecotSaslAuthNative for testing purposes.
 */
public class DovecotSaslAuthNativeMock extends DovecotSaslAuthNative {
    private final String[] responses;
    private int index = 0;

    public DovecotSaslAuthNativeMock(String response) {
        super(null);
        this.responses = response.split("\\n");
        this.inputStream = new InputStream() {
            @Override public int read() { return -1; }
            @Override public int read(byte[] b, int off, int len) {
                if (index >= responses.length) return -1;
                byte[] lineBytes = (responses[index] + "\n").getBytes();
                System.arraycopy(lineBytes, 0, b, off, lineBytes.length);
                index++;
                return lineBytes.length;
            }
        };
    }

    @Override void initSocket() { this.outputStream = new ByteArrayOutputStream(); }

    public String getSent() { return outputStream.toString(); }
}
