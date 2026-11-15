package cases;

import com.mimecast.robin.assertion.AssertException;
import com.mimecast.robin.config.client.CaseConfig;
import com.mimecast.robin.main.Client;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.main.RequestClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Integration suite: runs every JSON5 case under {@code src/test/resources/cases/config/integration/<area>/}.
 *
 * <p>Cases are discovered by folder, so adding coverage needs no Java. Each area folder groups one
 * capability (inbound, delivery, scanning, queue, relay, harness, ...). A case with a top-level
 * {@code request} block is an HTTP case run by {@link RequestClient}; any other case is an SMTP case
 * run by {@link Client}. Cases run concurrently and must not depend on each other.
 *
 * <p>The {@code selftest} folder holds cases that must fail; see {@link #selfTests()}.
 *
 * <p>Run the suite in the isolated stack (Robin, robin-sink, http-sink, test DNS, LMTP, SQL auth,
 * ClamAV and Rspamd): {@code .integration/ci.sh}.
 *
 * <p>Select cases with {@code -Drobin.integration.cases=<regex>}, matched against the case path
 * relative to the integration folder, for example {@code -Drobin.integration.cases='^(inbound|relay)/'}.
 */
@Tag("integration")
@Tag("suite")
public class IntegrationTest {

    private static final Path CASES = Path.of("src/test/resources/cases/config/integration");
    private static final String SELFTEST = "selftest";

    @BeforeAll
    static void before() throws ConfigurationException {
        String configDir = System.getProperty("robin.integration.clientConfig", ".integration/client-cfg");
        Path path = Path.of(configDir);
        for (String file : new String[]{"client.json5", "routes.json5", "server.json5", "properties.json5"}) {
            if (!Files.isRegularFile(path.resolve(file))) {
                throw new ConfigurationException("Missing integration client configuration: " + path.resolve(file));
            }
        }
        System.out.println("Integration client configuration: " + path.toAbsolutePath().normalize());
        Foundation.init(configDir);
    }

    // Zero invocations are allowed when the filter selects only self-tests; cases() rejects a filter
    // that selects nothing at all.
    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("cases")
    @DisplayName("Integration case")
    void integrationCase(String name) throws Exception {
        String path = CASES.resolve(name).toString();
        if (new CaseConfig(path).hasProperty("request")) {
            new RequestClient().request(path);
        } else {
            new Client().send(path);
        }
    }

    /**
     * Lists the cases to run as {@code area/name.json5}, sorted, excluding self-tests.
     *
     * <p>A case placed directly in the integration folder fails discovery instead of being skipped,
     * and so does a filter that selects nothing.
     */
    static Stream<String> cases() throws IOException {
        List<String> all = listCases();
        List<String> loose = all.stream().filter(name -> !name.contains("/")).toList();
        if (!loose.isEmpty()) {
            throw new IllegalStateException("Integration cases must live in an area folder: " + loose);
        }

        List<String> selected = all.stream()
                .filter(name -> !name.startsWith(SELFTEST + "/"))
                .filter(IntegrationTest::selected)
                .toList();
        if (selected.isEmpty() && !selected(SELFTEST + "/")) {
            throw new IllegalStateException("No integration cases match robin.integration.cases="
                    + System.getProperty("robin.integration.cases"));
        }
        return selected.stream();
    }

    private static List<String> listCases() throws IOException {
        try (Stream<Path> files = Files.walk(CASES, 2)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json5"))
                    .map(p -> CASES.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private static boolean selected(String name) {
        String filter = System.getProperty("robin.integration.cases", "");
        return filter.isBlank() || Pattern.compile(filter).matcher(name).find();
    }

    /**
     * Self-tests: cases that MUST fail, each with the reason it must fail for.
     *
     * <p>If one of these passes, an assertion type has stopped detecting failures and every
     * other case using it is suspect. The expected message guards against a case failing
     * for an unrelated reason, such as the stack being unreachable.
     */
    private static final Map<String, String> SELF_TESTS = new LinkedHashMap<>();

    static {
        SELF_TESTS.put("s01-wrong-reply-code.json5", "Assert unable to match [DATA] [^550]");
        SELF_TESTS.put("s02-log-verify-missing.json5", "did not match verify patterns");
        SELF_TESTS.put("s03-imap-wrong-subject.json5", "IMAP email verification failed: no header matched");
        SELF_TESTS.put("s04-imap-absent-but-delivered.json5", "IMAP email found when expected absent");
        SELF_TESTS.put("s05-imap-wrong-mailbox.json5", "IMAP email verification failed: no email found");
        SELF_TESTS.put("s06-log-refuse-matched.json5", "Found refuse pattern");
        SELF_TESTS.put("s07-protocol-none-matched.json5", "Assert matched refused [RCPT]");
        SELF_TESTS.put("s08-wrong-http-status.json5", "did not return status 404, got 200");
        SELF_TESTS.put("s09-log-verify-none-with-data.json5", "Request data found when none expected");
    }

    @TestFactory
    @DisplayName("Self-tests: assertions that must fail")
    Stream<DynamicTest> selfTests() throws IOException {
        Path folder = CASES.resolve(SELFTEST);
        try (Stream<Path> files = Files.list(folder)) {
            Set<String> found = files.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
            Assertions.assertEquals(SELF_TESTS.keySet(), found, "Every self-test case needs an expected failure");
        }

        return SELF_TESTS.entrySet().stream()
                .filter(entry -> selected(SELFTEST + "/" + entry.getKey()))
                .map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
                    AssertException e = Assertions.assertThrows(AssertException.class,
                            () -> new Client().send(folder.resolve(entry.getKey()).toString()),
                            entry.getKey() + " passed but must fail");
                    Assertions.assertTrue(e.getMessage() != null && e.getMessage().contains(entry.getValue()),
                            entry.getKey() + " failed for the wrong reason: " + e.getMessage());
                }));
    }
}
