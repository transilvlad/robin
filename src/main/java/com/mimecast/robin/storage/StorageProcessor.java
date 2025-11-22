package com.mimecast.robin.storage;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.ChaosHeaders;
import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.smtp.connection.Connection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Optional;

/**
 * Storage processor interface.
 * <p>Used to process emails after receiving the message.
 * <p>Processors can check for chaos headers to force specific return values for testing purposes.
 */
public interface StorageProcessor {

    /**
     * Processes storage for the given session.
     *
     * @param connection  Connection instance.
     * @param emailParser EmailParser instance.
     * @return Boolean.
     * @throws IOException On I/O error.
     */
    boolean process(Connection connection, EmailParser emailParser) throws IOException;

    /**
     * Checks for chaos headers that force a specific return value for this processor.
     * <p>This method should be called at the start of the processor's process() method.
     * If a forced return value is present, the processor should return it immediately
     * without performing normal processing.
     *
     * <p>The chaos header format is:
     * <pre>X-Robin-Chaos: LocalStorageClient; processor=ProcessorClassName; return=true/false</pre>
     *
     * @param emailParser EmailParser instance containing the email with potential chaos headers.
     * @return Optional containing the forced return value if chaos header is found, or empty otherwise.
     */
    default Optional<Boolean> getForcedReturnValue(EmailParser emailParser) {
        // Early return if chaos headers are not enabled to avoid unnecessary processing.
        if (!Config.getServer().isChaosHeaders()) {
            return Optional.empty();
        }

        if (emailParser == null) {
            return Optional.empty();
        }

        ChaosHeaders chaosHeaders = new ChaosHeaders(emailParser);

        if (!chaosHeaders.hasHeaders()) {
            return Optional.empty();
        }

        String processorClassName = this.getClass().getSimpleName();

        // Check for chaos headers matching LocalStorageClient.
        // Format: X-Robin-Chaos: LocalStorageClient; processor=ProcessorClassName; return=true/false
        for (MimeHeader header : chaosHeaders.getByValue(ChaosHeaders.TARGET_LOCAL_STORAGE_CLIENT)) {
            String processorParam = header.getParameter("processor");
            String returnParam = header.getParameter("return");

            // The processor parameter should match this processor's class name.
            // Format: processor=ProcessorClassName (e.g., processor=AVStorageProcessor, processor=SpamStorageProcessor).
            if (processorParam != null && processorParam.equals(processorClassName)) {
                if (returnParam != null) {
                    boolean forcedReturn = Boolean.parseBoolean(returnParam);
                    Logger log = LogManager.getLogger(this.getClass());
                    log.info("Chaos header forcing {} to return {}", processorClassName, forcedReturn);
                    return Optional.of(forcedReturn);
                }
            }
        }

        return Optional.empty(); // No matching chaos header found.
    }
}
