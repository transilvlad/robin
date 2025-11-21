package debug;

import com.mimecast.robin.imap.ImapClient;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

@Disabled
public class ImapClientDebug {

    @Test
    public void test() {
        String host = "mail.example.com";
        long port = 993L;
        String user = "tony@example.com";
        String pass = "giveHerTheRing";
        String folder = "INBOX";

        try (ImapClient client = new ImapClient(host, port, user, pass, folder)) {
            List<Message> messages = client.fetchEmails();
            if (messages.isEmpty()) {
                System.out.println("No messages found in folder " + folder);
                return;
            }

            System.out.println("Fetched " + messages.size() + " message(s) from " + host + "/" + folder);
            int idx = 1;
            for (Message m : messages) {
                try {
                    String subject = m.getSubject();
                    String from = m.getFrom() == null ? "unknown" : Arrays.toString(m.getFrom());
                    Date sent = m.getSentDate();
                    String[] msgIdHeader = m.getHeader("Message-ID");
                    String msgId = (msgIdHeader != null && msgIdHeader.length > 0) ? msgIdHeader[0] : "N/A";

                    System.out.printf("%d) Subject: %s%n   From: %s%n   Sent: %s%n   Message-ID: %s%n",
                            idx++, subject == null ? "(no subject)" : subject,
                            from, sent == null ? "unknown" : sent.toString(), msgId);
                } catch (MessagingException me) {
                    System.err.println("Error reading message metadata: " + me.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("IMAP operation failed: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
