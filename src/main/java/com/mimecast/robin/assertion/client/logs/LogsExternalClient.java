package com.mimecast.robin.assertion.client.logs;

import com.mimecast.robin.assertion.AssertException;
import com.mimecast.robin.assertion.client.MatchExternalClient;
import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.config.assertion.external.logs.LogsExternalClientConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.util.Magic;
import com.mimecast.robin.util.Sleep;
import com.mimecast.robin.util.UIDExtractor;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Logs external client.
 * <p>This provides a means to fetch the logs from a local file.
 */
public class LogsExternalClient extends MatchExternalClient {

    // Storage dir and log file.
    protected String dir;
    protected String fileName;
    protected String path;

    /**
     * Constructs a new LogsExternalClient instance.
     */
    public LogsExternalClient() {
        dir = Config.getProperties().getStringProperty("localLogsDir", Config.getProperties().getStringProperty("logs.local.dir", ""));
        fileName = new SimpleDateFormat("yyyyMMdd", Config.getProperties().getLocale()).format(new Date()) + ".log";

        setPath(fileName);
    }

    /**
     * Sets config instance.
     *
     * @param config LogsExternalClient instance.
     * @return Self.
     */
    @Override
    public LogsExternalClient setConfig(BasicConfig config) {
        this.config = new LogsExternalClientConfig(config.getMap());

        // Update file path.
        setPath(((LogsExternalClientConfig) this.config).getLogPrecedence() + fileName);
        verifyNone = this.config.getVerifyNone();

        return this;
    }

    /**
     * Sets path.
     *
     * @param fileName String.
     */
    protected void setPath(String fileName) {
        path = Paths.get(dir, fileName).toString();
    }

    /**
     * Run assertions.
     *
     * <p>With {@code verifyNone} the grep must find no lines. Otherwise lines must be found and satisfy {@code verify}
     * before {@code match}, {@code refuse} and {@code magic} run. Failures throw unless {@code verifyFails} is false,
     * which skips instead.
     *
     * @throws AssertException Assertion exception.
     */
    @Override
    public void run() throws AssertException {
        if (config.isEmpty()) {
            return;
        }

        if (!config.checkCondition(connection)) {
            log.info("Condition not met, skipping: {}", config.getStringProperty("condition"));
            return;
        }

        if (!verifyNone) {
            compileVerify(); // Precompile verify patterns for performance.
        }

        List<String> data = getLogs();

        if (verifyNone) {
            if (data.isEmpty()) {
                log.info("AssertExternal logs verify none success");
            } else {
                logResults(data);
                fail("Logs found when none expected: " + data.get(0));
            }

        } else if (data.isEmpty()) {
            fail("No logs found to assert against");

        } else if (!checkVerify(data)) {
            logResults(data);
            fail("Logs did not match verify patterns " + verifyPatterns);

        } else {
            runMatches(data);
        }
    }

    /**
     * Fails the assertion, or skips when verify failures are not fatal.
     *
     * @param message Failure message.
     * @throws AssertException When verify failures are fatal.
     */
    protected void fail(String message) throws AssertException {
        if (assertVerifyFails) {
            throw new AssertException(message);
        }
        log.warn("{}, skipping", message);
        skip = true;
    }

    /**
     * Get logs.
     *
     * <p>Retries until {@code verify} is satisfied or attempts are spent.
     * With {@code verifyNone} it retries while no lines are found, so absence holds across the whole window.
     *
     * @return List of String.
     * @throws AssertException Assertion exception.
     */
    protected List<String> getLogs() throws AssertException {
        List<String> data = new ArrayList<>();

        if (dir.isEmpty()) {
            throw new AssertException("AssertExternal localLogsDir not found in properties");
        }

        String uid = UIDExtractor.getUID(connection, transactionId);
        List<Grep> greps = getGreps();
        boolean count = greps.stream().anyMatch(Grep::count);

        long delay = config.getWait() > 0 ? config.getWait() * 1000L : 0L;
        for (int attempt = 0; attempt < config.getRetry(); attempt++) {
            Sleep.nap((int) delay);
            delay = config.getDelay() * 1000L; // Retry delay.
            log.info("AssertExternal logs fetching locally, attempt {} of {}", attempt + 1, config.getRetry());

            data.clear();
            try (BufferedReader br = new BufferedReader(new FileReader(path))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (greps.isEmpty() ? (uid == null || line.contains(uid)) : Grep.all(greps, line)) {
                        data.add(line);
                    }
                }
            } catch (IOException e) {
                throw new AssertException("AssertExternal logs reading problems: " + e.getMessage());
            }

            // `grep -c` replaces the lines with how many there were.
            if (count) {
                int size = data.size();
                data.clear();
                data.add(String.valueOf(size));
            }

            if (verifyNone ? !data.isEmpty() : (!data.isEmpty() && checkVerify(data))) {
                break;
            }
            log.info("AssertExternal logs fetch verify {}", (attempt < config.getRetry() - 1 ? "failure" : "attempts spent"));
        }

        return data;
    }

    /**
     * Prepare grep patterns.
     *
     * <p>Parameters follow grep: {@code E} compiles the pattern as a regex instead of a literal,
     * {@code v} keeps lines that do not match, and {@code c} returns the count of kept lines.
     *
     * @return List of Grep.
     */
    @SuppressWarnings("unchecked")
    protected List<Grep> getGreps() {
        List<Grep> greps = new ArrayList<>();

        ((List<Map<String, String>>) config.getListProperty("grep"))
                .forEach(map -> {
                    String pattern = Magic.magicReplace(map.get("pattern"), connection.getSession());
                    String parameter = map.getOrDefault("parameter", "");

                    greps.add(new Grep(
                            Pattern.compile(parameter.contains("E") ? pattern : Pattern.quote(pattern), Pattern.CASE_INSENSITIVE),
                            parameter.contains("v"),
                            parameter.contains("c")
                    ));
                });

        return greps;
    }

    /**
     * Compiled grep filter.
     *
     * @param pattern Compiled pattern.
     * @param invert  Keep lines that do not match.
     * @param count   Return the count of kept lines.
     */
    protected record Grep(Pattern pattern, boolean invert, boolean count) {

        /**
         * Does the line pass this filter.
         *
         * @param line Log line.
         * @return Boolean.
         */
        boolean keeps(String line) {
            return pattern.matcher(line).find() != invert;
        }

        /**
         * Does the line pass every filter.
         *
         * @param greps List of Grep.
         * @param line  Log line.
         * @return Boolean.
         */
        static boolean all(List<Grep> greps, String line) {
            return greps.stream().allMatch(grep -> grep.keeps(line));
        }
    }
}
