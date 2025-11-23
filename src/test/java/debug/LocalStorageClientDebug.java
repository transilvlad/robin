package debug;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.main.Server;
import com.mimecast.robin.queue.RelayQueueCron;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.connection.ConnectionMock;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.storage.LocalStorageClient;
import com.mimecast.robin.storage.LocalStorageClientMock;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Executors;

@Disabled
public class LocalStorageClientDebug {

    /**
     * Process an email using LocalStorageClient for debugging purposes.
     * <p>Adjust the configuration in "cfg/" as needed.
     * <p>The initial goal is to test the email analysis bot.
     * <p>This requires AV and SPAM scanners to be enabled in the configuration
     * as it requires their results to fill in parts of the report.
     */
    @Test
    public void processEmailAnalysisBot() throws IOException, ConfigurationException, InterruptedException {
        // Initialize foundation and create a mock connection.
        Foundation.init("cfg/");
        Connection connection = new ConnectionMock(new Session());

        // Create a message envelope with a bot address recipient.
        // Adjust the bot address as needed to set your reply address.
        String botAddress = "robotEmail+tony+gmail.com@example.com";
        MessageEnvelope envelope = new MessageEnvelope()
                .setMail("mailFrom@example.com")
                .addRcpt(botAddress);
        envelope.addBotAddress(botAddress, "email");
        connection.getSession().addEnvelope(envelope);

        // Set IP and rDNS.
        connection.getSession().setFriendAddr("1.1.1.1");
        connection.getSession().setFriendRdns("example.com");

        // Enable required features in the server configuration.
        var server = Config.getServer();
        server.getStorage().getMap().put("enabled", true);
        server.getRelay().getMap().put("enabled", false);
        server.getStorage().getMap().put("autoDelete", true);
        server.getClamAV().getMap().put("enabled", true);
        server.getRspamd().getMap().put("enabled", true);
        server.getQueue().getMap().put("queueInitialDelay", 0L); // No delay for testing.
        server.getRspamd().getMap().put("rejectThreshold", 250.0); // Avoid SPAM rejection for testing.

        // Create a local storage client and write the test email to it.
        LocalStorageClient localStorageClient = new LocalStorageClientMock(server)
                .setConnection(connection)
                .setExtension("dat");

        var path = Paths.get("src/test/resources/cases/sources/lipsum.eml");
        localStorageClient.getStream().write(Files.readAllBytes(path));

        // Start bot executor as it runs on a separate thread.
        ServerMock.startBotExecutor();

        // Save the email which triggers the bot processing.
        localStorageClient.save();

        // Initial wait for bot to finish queueing report for delivery.
        Thread.sleep(5000);

        // Run the relay queue cron to process the bot queue immediately.
        // Likely email delivery will fail unless your recipient server is reachable and accepts the email immediately.
        RelayQueueCron.run();

        // Close session to delete original email file.
        // Queued files are deleted by the queue processor when finished.
        connection.getSession().close();
    }

    /**
     * Server mock to start bot executor.
     */
    class ServerMock extends Server {
        public static void startBotExecutor() {
            botExecutor = Executors.newCachedThreadPool();
        }
    }
}
