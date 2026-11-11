package com.mimecast.robin.mime;

import com.mimecast.robin.smtp.MessageEnvelope;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HeaderFramingTest {
    @Test
    void prohibitedFramingCannotBeHiddenAtAnyChunkBoundaryOrByRetrying() {
        byte[] raw = "From: first@origin.test\r\r\nFrom: second@other.test\r\n\r\nBody"
                .getBytes(StandardCharsets.US_ASCII);
        for (int cut = 0; cut <= raw.length; cut++) {
            HeaderFraming framing = new HeaderFraming();
            int split = cut;
            assertThrows(HeaderFraming.InvalidHeaderException.class, () -> {
                framing.accept(raw, 0, split);
                framing.accept(raw, split, raw.length - split);
                framing.finish();
            });
            assertThrows(HeaderFraming.InvalidHeaderException.class, framing::finish);
        }
    }

    @Test
    void bytewiseAndBulkValidationPreserveUtf8FoldingAndAllOpaqueBodyBytes() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write("From: first@origin.test\nX-Note: ".getBytes(StandardCharsets.US_ASCII));
        for (int c = 0x80; c <= 0x9f; c++) raw.write(Character.toString(c).getBytes(StandardCharsets.UTF_8));
        raw.write("\r\n\tfolded\n\r\n".getBytes(StandardCharsets.US_ASCII));
        for (int b = 0; b < 256; b++) raw.write(b);
        byte[] bytes = raw.toByteArray();
        for (boolean bytewise : new boolean[]{true, false}) {
            HeaderFraming framing = new HeaderFraming();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (var checked = framing.receivingStream(output)) {
                if (bytewise) {
                    for (byte b : bytes) checked.write(b & 0xff);
                } else {
                    checked.write(bytes);
                }
                framing.finish();
            }
            assertArrayEquals(bytes, output.toByteArray());
        }
        HeaderFraming.validate(new ByteArrayInputStream(bytes));
    }

    @Test
    void incompleteChunkIsNotAnIncompleteMessageAndCloseIsNotAnEndMarker() throws Exception {
        HeaderFraming framing = new HeaderFraming();
        try (var first = framing.receivingStream(new ByteArrayOutputStream())) {
            first.write("From: first@origin.test\r".getBytes(StandardCharsets.US_ASCII));
        }
        try (var second = framing.receivingStream(new ByteArrayOutputStream())) {
            second.write("\n\r\nBody\r".getBytes(StandardCharsets.US_ASCII));
        }
        framing.finish();
    }

    @Test
    void envelopeClonesDoNotShareIncompleteReceiptValidationState() throws Exception {
        MessageEnvelope received = new MessageEnvelope();
        byte[] partial = "From: first@origin.test\r".getBytes(StandardCharsets.US_ASCII);
        received.getHeaderFraming().accept(partial, 0, partial.length);
        MessageEnvelope copy = received.clone();
        byte[] fresh = "From: second@other.test\r\n\r\nBody".getBytes(StandardCharsets.US_ASCII);
        copy.getHeaderFraming().accept(fresh, 0, fresh.length);
        copy.getHeaderFraming().finish();
        assertThrows(HeaderFraming.InvalidHeaderException.class, received.getHeaderFraming()::finish);
    }
}
