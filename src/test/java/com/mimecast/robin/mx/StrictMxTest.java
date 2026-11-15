package com.mimecast.robin.mx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StrictMxTest {

    /**
     * Without MTA-STS (for example an unreadable truststore) delivery must be retried with a
     * routing error, not fail with a NullPointerException that skips the 451 handling.
     */
    @Test
    void uninitializedStsFailsAsTransientRoutingError() {
        StrictMx strictMx = new StrictMx("remote.test", null);

        MailRoutingException policy = assertThrows(MailRoutingException.class, strictMx::getPolicy);
        assertFalse(policy.isPermanent());
        assertTrue(policy.getMessage().contains("MTA-STS policy check unavailable for remote.test"));

        MailRoutingException records = assertThrows(MailRoutingException.class, strictMx::getMxRecords);
        assertFalse(records.isPermanent());
    }
}
