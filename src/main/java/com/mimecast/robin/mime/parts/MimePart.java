package com.mimecast.robin.mime.parts;

import com.mimecast.robin.mime.HashType;
import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.mime.headers.MimeHeaders;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeUtility;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * MIME part container abstract.
 */
public abstract class MimePart {

    /**
     * Part headers.
     */
    protected MimeHeaders headers = new MimeHeaders();

    /**
     * Part body as input stream.
     */
    protected InputStream body;

    /**
     * Part body as string.
     */
    protected String content;

    /**
     * Part size.
     */
    protected long size;

    /**
     * Part hashes.
     */
    protected HashMap<String, String> hashes = new HashMap<>();

    /**
     * Adds given instance header.
     *
     * @param header MimeHeader instance.
     * @return Self.
     */
    public MimePart addHeader(MimeHeader header) {
        headers.put(header);
        return this;
    }

    /**
     * Adds header with given anme and value.
     *
     * @param name  Header name.
     * @param value Header value.
     * @return Self.
     */
    public MimePart addHeader(String name, String value) {
        headers.put(new MimeHeader(name, value));
        return this;
    }

    public void setHeaders(MimeHeaders headers) {
        this.headers = headers;
    }

    /**
     * Gets headers container.
     *
     * @return MimeHeaders instance.
     */
    public MimeHeaders getHeaders() {
        return headers;
    }

    /**
     * Gets header by name.
     * <p>Case insensitive.
     *
     * @param name Header name.
     * @return Self.
     */
    public MimeHeader getHeader(String name) {
        for (MimeHeader header : headers.get()) {
            if (header.getName().equalsIgnoreCase(name)) {
                return header;
            }
        }

        return null;
    }

    /**
     * Writes email to given output stream.
     *
     * @param outputStream OutputStream instance.
     * @return Self.
     * @throws IOException Unable to write to output stream.
     */
    public MimePart writeTo(OutputStream outputStream) throws IOException {
        // Write headers.
        for (MimeHeader header : headers.get()) {
            outputStream.write(header.toString().getBytes());
        }

        // Write separator.
        outputStream.write("\r\n".getBytes());

        // Wrap output stream with encoder if set.
        OutputStream writeStream = outputStream;

        MimeHeader contentEncoding = getHeader("Content-Transfer-Encoding");
        if (contentEncoding != null) {
            try {
                String encoding = contentEncoding.getValue().toLowerCase();
                if (encoding.contains("quoted-printable")) {
                    writeStream = MimeUtility.encode(outputStream, "quoted-printable");
                } else if (encoding.contains("base64")) {
                    writeStream = MimeUtility.encode(outputStream, "base64");
                }
            } catch (MessagingException e) {
                throw new IOException("Unable to get encoding stream", e);
            }
        }

        // Write content.
        writeStream.write(getBytes());
        writeStream.flush();

        // Ensure empty line at the end.
        outputStream.write("\r\n".getBytes());

        return this;
    }

    /**
     * Gets content as bytes.
     *
     * @return String.
     * @throws IOException Unable to read stream.
     */
    public byte[] getBytes() throws IOException {
        if (body.markSupported()) {
            body.reset();
        }
        return body.readAllBytes();
    }

    /**
     * Gets size.
     *
     * @return Long.
     */
    public long getSize() {
        return size;
    }

    /**
     * Sets size.
     *
     * @param size Long.
     */
    public void setSize(long size) {
        this.size = size;
    }

    /**
     * Gets hash by type.
     *
     * @return HashType instance.
     */
    public List<String> getHashesList() {
        return new ArrayList<>(hashes.values());
    }

    /**
     * Gets hash by type.
     *
     * @param hashType HashType instance.
     * @return HashType instance.
     */
    public String getHash(HashType hashType) {
        return hashes.get(hashType.getKey());
    }

    /**
     * Sets hash by type.
     *
     * @param key   HashType instance.
     * @param value Hash value.
     */
    public void setHash(HashType key, String value) {
        hashes.put(key.getKey(), value);
    }

    /**
     * Returns a string representation of the object.
     *
     * @return String.
     */
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("len=").append(getSize());
        for (String key : hashes.keySet()) {
            builder.append(",").append(key).append("=").append(hashes.get(key));
        }
        return builder.toString();
    }
}