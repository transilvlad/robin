package com.mimecast.robin.mime.parts;

import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.util.CharsetDetector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.mail.internet.MimeUtility;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/**
 * MIME part container from string.
 */
public class TextMimePart extends MimePart {
    private static final Logger log = LogManager.getLogger(TextMimePart.class);

    /**
     * Constructs a new TextMimePart instance with given string.
     *
     * @param content Body content.
     */
    public TextMimePart(byte[] content) {
        body = new ByteArrayInputStream(content);
    }

    /**
     * Writes email to given output stream.
     *
     * @param outputStream OutputStream instance.
     * @return Self.
     * @throws IOException Unable to write to output stream.
     */
    @Override
    public MimePart writeTo(OutputStream outputStream) throws IOException {
        // Ensure we have a Content-Type header.
        MimeHeader contentType = getHeader("Content-Type");
        if (contentType == null) {
            headers.put(new MimeHeader("Content-Type", "text/plain; charset=\"UTF-8\""));
        }

        super.writeTo(outputStream);
        return this;
    }

    /**
     * Gets content.
     *
     * @return Content String.
     * @throws IOException Unable to read stream.
     */
    public String getContent() throws IOException {
        if (content == null) {
            byte[] bytes = getBytes();
            MimeHeader type = getHeader("Content-Type");
            String declared = type == null ? null : type.getParameter("charset");
            if (declared != null) {
                try {
                    content = Charset.forName(MimeUtility.javaCharset(declared)).newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString();
                    return content;
                } catch (IllegalCharsetNameException | UnsupportedCharsetException | CharacterCodingException e) {
                    log.warn("Unable to decode text with declared charset; using charset detection ({})",
                            e.getClass().getSimpleName());
                }
            }
            String detected = CharsetDetector.getCharset(bytes);
            try {
                Charset charset = detected == null ? Charset.defaultCharset() : Charset.forName(detected);
                content = new String(bytes, charset);
            } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                throw new IOException("Unable to resolve detected text charset", e);
            }
        }

        return content;
    }
}
