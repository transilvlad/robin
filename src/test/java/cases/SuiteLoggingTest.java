package cases;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.rolling.CompositeTriggeringPolicy;
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.appender.rolling.TimeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SuiteLoggingTest {
    @TempDir
    Path dir;

    @Test
    void productionLoggingLoadsWithBoundedArchivesAndVisibleAuditOutcomes() throws Exception {
        Path file = dir.resolve("log4j2.xml");
        Files.writeString(file, Files.readString(Path.of(".suite", "cfg", "log4j2.xml"))
                .replace("/var/log/robin", dir.toString()));
        try (LoggerContext context = new LoggerContext("suite-logging-test")) {
            var config = ConfigurationFactory.getInstance().getConfiguration(context, "suite", file.toUri());
            context.start(config);
            assertEquals(Level.WARN, config.getLoggerConfig("com.mimecast.robin.storage").getLevel());
            assertEquals(Level.INFO, config.getLoggerConfig("com.mimecast.robin.main").getLevel());
            assertEquals(Level.INFO, config.getLoggerConfig("com.mimecast.robin.audit").getLevel());
            var appender = assertInstanceOf(RollingFileAppender.class, config.getAppender("appender"));
            var policy = assertInstanceOf(CompositeTriggeringPolicy.class, appender.getTriggeringPolicy());
            assertTrue(Arrays.stream(policy.getTriggeringPolicies()).anyMatch(TimeBasedTriggeringPolicy.class::isInstance));
            var sizePolicy = Arrays.stream(policy.getTriggeringPolicies())
                    .filter(SizeBasedTriggeringPolicy.class::isInstance)
                    .map(SizeBasedTriggeringPolicy.class::cast).findFirst().orElseThrow();
            assertEquals(50L * 1024 * 1024, sizePolicy.getMaxFileSize());
            context.getLogger("com.mimecast.robin.audit").info("test audit outcome");
        }
        assertTrue(Files.readString(dir.resolve("robin.log")).contains("test audit outcome"));
    }
}
