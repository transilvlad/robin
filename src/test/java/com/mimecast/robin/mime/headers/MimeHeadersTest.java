package com.mimecast.robin.mime.headers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MimeHeadersTest {
    @Test
    void suppliedFilenameResolutionPreservesPrecedenceAndValues() {
        MimeHeaders headers = new MimeHeaders()
                .put(new MimeHeader("Content-Disposition", "attachment; filename=\"Report+Name.XML\"; filename*=UTF-8''other.xml"))
                .put(new MimeHeader("Content-Type", "application/octet-stream; name=type.xml"));
        assertEquals("Report+Name.XML", headers.getFileName());
        assertEquals("caf\u00e9.xml", new MimeHeaders()
                .put(new MimeHeader("Content-Disposition", "attachment; filename*=UTF-8''caf%C3%A9.xml"))
                .put(new MimeHeader("Content-Type", "application/octet-stream; name=type.xml")).getFileName());
        assertEquals("caf\u00e9.json", new MimeHeaders()
                .put(new MimeHeader("Content-Type", "application/octet-stream; name*=ISO-8859-1''caf%E9.json")).getFileName());
        assertNull(new MimeHeaders().getFileName());
        assertNull(new MimeHeaders().put(new MimeHeader("Content-Disposition",
                "attachment; filename*0*=UTF-8''report.; filename*1*=xml")).getFileName());
    }
}
