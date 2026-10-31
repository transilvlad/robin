package com.mimecast.robin.smtp.transaction;

import com.mimecast.robin.main.Foundation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class SessionTransactionListTest {
    // Serialized before LMTP/LOCAL support, including the legacy three-entry instance repeatable field.
    private static final String LEGACY_HISTORY =
            "rO0ABXNyADpjb20ubWltZWNhc3Qucm9iaW4uc210cC50cmFuc2FjdGlvbi5TZXNzaW9uVHJhbnNhY3Rpb25MaXN0xlHZbCiMKfYCAAFM"
            + "AAllbnZlbG9wZXN0ABBMamF2YS91dGlsL0xpc3Q7eHIAM2NvbS5taW1lY2FzdC5yb2Jpbi5zbXRwLnRyYW5zYWN0aW9uLlRyYW5z"
            + "YWN0aW9uTGlzdAAAAAAAAAABAgACTAAKcmVwZWF0YWJsZXEAfgABTAAMdHJhbnNhY3Rpb25zcQB+AAF4cHNyABpqYXZhLnV0aWwuQXJy"
            + "YXlzJEFycmF5TGlzdNmkPL7NiAbSAgABWwABYXQAE1tMamF2YS9sYW5nL09iamVjdDt4cHVyABNbTGphdmEubGFuZy5TdHJpbmc7rdJW"
            + "5+kde0cCAAB4cAAAAAN0AARTTVRQdAAEUkNQVHQABEJEQVRzcgATamF2YS51dGlsLkFycmF5TGlzdHiB0h2Zx2GdAwABSQAEc2l6ZXhw"
            + "AAAAAHcEAAAAAHhzcQB+AAwAAAABdwQAAAABc3IAO2NvbS5taW1lY2FzdC5yb2Jpbi5zbXRwLnRyYW5zYWN0aW9uLkVudmVsb3BlVHJh"
            + "bnNhY3Rpb25MaXN0M49T7deXhRkCAAB4cQB+AAJzcQB+AAR1cQB+AAcAAAADcQB+AAlxAH4ACnEAfgALc3EAfgAMAAAAAXcEAAAAAXNy"
            + "AC9jb20ubWltZWNhc3Qucm9iaW4uc210cC50cmFuc2FjdGlvbi5UcmFuc2FjdGlvbgAAAAAAAAABAgAEWgAFZXJyb3JMAAdjb21tYW5kdAAS"
            + "TGphdmEvbGFuZy9TdHJpbmc7TAAHcGF5bG9hZHEAfgAVTAAIcmVzcG9uc2VxAH4AFXhwAHEAfgAKdAAiUkNQVCBUTzo8b3JpZ2luYWxA"
            + "Zml4dHVyZS5pbnZhbGlkPnQADDI1MCBBZG1pdHRlZHh4";

    @Test
    void legacySerializedHistoryUsesCurrentCaseInsensitiveRepeatability() throws Exception {
        SessionTransactionList restored;
        try (var input = new ObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(LEGACY_HISTORY)))) {
            restored = (SessionTransactionList) input.readObject();
        }
        var envelope = restored.getEnvelopes().getFirst();
        assertEquals(3715280498593334553L, ObjectStreamClass.lookup(EnvelopeTransactionList.class).getSerialVersionUID());
        assertEquals("original@fixture.invalid", envelope.getRcpt().getFirst().getAddress());
        envelope.addTransaction("lmtp", "RCPT TO:<first@fixture.invalid>", "250 Delivered");
        envelope.addTransaction("LMTP", "RCPT TO:<second@fixture.invalid>", "451 Deferred", true);
        envelope.addTransaction("lmtp", "250 Scripted");
        envelope.addTransaction("LMTP", "451 Scripted", true);
        restored.addTransaction("local", "550 First", true);
        restored.addTransaction("LOCAL", "451 Second", true);
        restored.addTransaction("quit", "221 First");
        restored.addTransaction("QUIT", "221 Duplicate");
        assertEquals(4, envelope.getLmtp().size());
        assertEquals(2, restored.getTransactions("LoCaL").size());
        assertEquals(1, restored.getTransactions("QUIT").size());
        assertEquals(4, envelope.clone().getLmtp().size());
        var output = new ByteArrayOutputStream();
        try (var stream = new ObjectOutputStream(output)) { stream.writeObject(restored); }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            var roundTrip = (SessionTransactionList) input.readObject();
            assertEquals(4, roundTrip.getEnvelopes().getFirst().getLmtp().size());
            assertEquals(2, roundTrip.getTransactions("LOCAL").size());
        }
        assertFalse(java.util.Arrays.stream(ObjectStreamClass.lookup(TransactionList.class).getFields())
                .anyMatch(field -> field.getName().equals("repeatable") || field.getName().equals("REPEATABLE")));
    }

    @Test
    void repeatedLmtpFinalRepliesSurviveCloningWithoutChangingRcptAdmission() {
        var envelope = new EnvelopeTransactionList();
        for (String recipient : java.util.List.of("delivered@example.com", "temporary@example.com", "unknown@example.com")) {
            envelope.addTransaction("RCPT", "RCPT TO:<" + recipient + ">", "250 Admitted");
        }
        envelope.addTransaction("LMTP", "RCPT TO:<delivered@example.com>", "250 Delivered");
        envelope.addTransaction("LMTP", "RCPT TO:<temporary@example.com>", "451 Later", true);
        envelope.addTransaction("LMTP", "RCPT TO:<unknown@example.com>", "", true);
        var copied = envelope.clone();
        assertEquals(3, copied.getLmtp().size());
        assertEquals(3, copied.getRecipients().size());
        assertTrue(copied.getRcptErrors().isEmpty());
        assertEquals(java.util.List.of("temporary@example.com", "unknown@example.com"), copied.getFailedRecipients());
        copied.getLmtp().getFirst().setResponse("550 Changed").setError(true);
        assertEquals("250 Delivered", envelope.getLmtp().getFirst().getResponse());
        assertFalse(envelope.getLmtp().getFirst().isError());
    }

    @Test
    void aggregateConfirmationRequiresFinalDataOrLastChunkAndKeepsUnknownLmtpRecipients() {
        var envelope = new EnvelopeTransactionList();
        envelope.addTransaction("RCPT", "RCPT TO:<first@example.com>", "250 Admitted");
        assertFalse(envelope.isMessageCommitted());
        assertEquals(java.util.List.of("first@example.com"),
                envelope.getUndeliveredLmtpRecipients(java.util.List.of("first@example.com")));
        envelope.addTransaction("BDAT", "BDAT 1", "250 Received chunk");
        assertFalse(envelope.isMessageCommitted());
        envelope.addTransaction("BDAT", "BDAT 1 LAST", "250 Delivered");
        assertTrue(envelope.isMessageCommitted());
        assertTrue(envelope.getUndeliveredLmtpRecipients(java.util.List.of("first@example.com")).isEmpty());
        envelope.addTransaction("LMTP", "RCPT TO:<first@example.com>", "550 Failed", true);
        assertEquals(java.util.List.of("first@example.com"),
                envelope.getUndeliveredLmtpRecipients(java.util.List.of("first@example.com")),
                "LMTP final failure must override a positive aggregate response");
    }

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @Test
    void testWithEnvelope() {
        SessionTransactionList session = new SessionTransactionList();

        // SMTP
        String smtp = "220 example.com ESMTP";
        session.addTransaction("SMTP", smtp, false);

        assertEquals(smtp, session.getLast("SMTP").getResponse());
        assertNull(session.getLast("QUIT"));

        // EHLO
        String ehlo = "250 Welcome";
        session.addTransaction("EHLO", ehlo, true);

        assertEquals(ehlo, session.getLast("EHLO").getResponse());
        assertTrue(session.getLast("EHLO").isError());

        // QUIT
        String quit = "221 Bye";
        session.addTransaction("QUIT", quit);
        session.addTransaction("QUIT", quit); // Skipped

        assertEquals(quit, session.getLast("QUIT").getResponse());

        // Envelope
        EnvelopeTransactionList envelope = new EnvelopeTransactionList();
        session.addEnvelope(envelope);

        assertEquals(1, session.getEnvelopes().size());
        assertNull(session.getEnvelopes().get(0).getMail());
        assertTrue(session.getEnvelopes().get(0).getRcpt().isEmpty());
        assertNull(session.getEnvelopes().get(0).getData());
        assertTrue(session.getEnvelopes().get(0).getBdat().isEmpty());

        // MAIL
        String mailp = "MAIL FROM: <tony@example.com>";
        String mailr = "250 Sender OK [7qGJZ4oRNkWJPsu_7ug1nw.localhost]";
        envelope.addTransaction("MAIL", mailp, mailr, false);

        assertEquals(mailp, session.getEnvelopes().get(0).getMail().getPayload());
        assertEquals(mailr, session.getEnvelopes().get(0).getMail().getResponse());

        // RCPT
        String rcptp = "RCPT TO: <pepper@xample.com>";
        String rcptr = "250 Recipient OK";
        envelope.addTransaction("RCPT", rcptr, true);
        envelope.addTransaction("RCPT", rcptp, rcptr);

        assertEquals(rcptr, session.getEnvelopes().get(0).getRcpt().get(0).getResponse());
        assertEquals(rcptp, session.getEnvelopes().get(0).getRcpt().get(1).getPayload());
        assertFalse(session.getEnvelopes().get(0).getRcpt().get(1).isError());
        assertEquals(1, session.getEnvelopes().get(0).getRcptErrors().size());

        // DATA
        String data = "250 Received OK";
        envelope.addTransaction("DATA", data, false);

        assertEquals(data, session.getEnvelopes().get(0).getData().getResponse());

        // BDAT
        String bdatp = "BDAT 123 LAST";
        String bdatr = "250 OK";
        envelope.addTransaction("BDAT", bdatp, bdatr, false);

        assertEquals(bdatp, session.getEnvelopes().get(0).getBdat().get(0).getPayload());
        assertEquals(bdatr, session.getEnvelopes().get(0).getBdat().get(0).getResponse());

        // Errors
        assertEquals(1, session.getErrors().size());
    }
}
