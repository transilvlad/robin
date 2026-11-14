package com.mimecast.robin.assertion.client.request;

import com.google.gson.Gson;
import com.mimecast.robin.assertion.AssertException;
import com.mimecast.robin.assertion.client.MatchExternalClient;
import com.mimecast.robin.config.BasicConfig;
import com.mimecast.robin.config.assertion.external.MatchExternalClientConfig;
import com.mimecast.robin.config.client.RequestConfig;
import com.mimecast.robin.http.HttpResponse;
import com.mimecast.robin.main.RequestBase;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.util.MapUtils;
import com.mimecast.robin.util.Sleep;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Request external client.
 *
 * <p>This is a basic HTTP request client.</p>
 * <p>Each attempt makes the request and collects its data. An attempt succeeds when the response status is
 * acceptable and the data satisfies {@code verify}; otherwise it is retried within {@code retry} and {@code delay}.
 * With {@code verifyNone} the data must stay empty on every attempt. When attempts are spent the assertion fails,
 * or skips when {@code verifyFails} is false.</p>
 * <p>The optional {@code status} key sets the exact HTTP status code expected; by default any 2xx is accepted.</p>
 * <p>Example configuration:</p>
 * <pre>
 * {
 *   "type": "request",
 *   "wait": 5,
 *   "delay": 5,
 *   "retry": 3,
 *   "status": 200,
 *   request: {
 *     url: "https://robin.requestcatcher.com/",
 *     type: "POST",
 *     headers: [
 *       {
 *         name: "Content-Type",
 *         value: "application/json"
 *       },
 *       {
 *         name: "Cache-Control",
 *         value: "no-cache"
 *       },
 *       {
 *         name: "Authorization",
 *         value: "Basic {$requestAuth}"
 *       }
 *     ],
 *     content: {
 *       path: "src/test/resources/cases/config/request/example.json5",
 *       mimeType: "application/json"
 *     }
 *   }
 * },
 * </pre>
 */
public class RequestExternalClient extends MatchExternalClient {

    /**
     * Assert external config instance.
     */
    protected MatchExternalClientConfig config;

    /**
     * Sets config instance.
     *
     * @param config ExternalClient instance.
     * @return Self.
     */
    @Override
    public RequestExternalClient setConfig(BasicConfig config) {
        super.setConfig(config);
        this.config = new MatchExternalClientConfig(config.getMap());
        return this;
    }

    /**
     * Run assertions.
     *
     * @throws AssertException Assertion exception.
     */
    @Override
    public void run() throws AssertException {
        if (!config.checkCondition(connection)) {
            log.info("Condition not met, skipping: {}", config.getStringProperty("condition"));
            return;
        }

        verifyNone = config.getVerifyNone();
        compileVerify();

        Long expectedStatus = config.hasProperty("status") ? config.getLongProperty("status") : null;
        HttpResponse httpResponse = null;
        List<String> data = new ArrayList<>();

        long delay = config.getWait() > 0 ? config.getWait() * 1000L : 0L;
        for (int count = 0; count < config.getRetry(); count++) {
            Sleep.nap((int) delay);
            delay = config.getDelay() * 1000L;
            log.info("AssertExternal request attempt {} of {}", count + 1, config.getRetry());

            httpResponse = makeRequest();
            if (!isAcceptable(httpResponse, expectedStatus)) {
                continue;
            }

            data = getData(httpResponse);
            if (verifyNone) {
                if (!data.isEmpty()) {
                    logResults(data);
                    fail("Request data found when none expected: " + data.get(0));
                    return;
                }
                continue; // Absence must hold for every attempt.
            }

            if (!data.isEmpty() && checkVerify(data)) {
                log.debug("AssertExternal request response verify success");
                logResults(data);
                runMatches(data);
                return;
            }
        }

        if (!isAcceptable(httpResponse, expectedStatus)) {
            fail("Request did not return " + (expectedStatus != null ? "status " + expectedStatus : "a successful status")
                    + (httpResponse != null ? ", got " + httpResponse.getStatusCode() : ", no response"));
        } else if (verifyNone) {
            log.info("AssertExternal request verify none success");
        } else if (data.isEmpty()) {
            fail("No request data found to assert against");
        } else {
            logResults(data);
            fail("Request data did not match verify patterns " + verifyPatterns);
        }
    }

    /**
     * Fails the assertion, or skips when verify failures are not fatal.
     *
     * @param message Failure message.
     * @throws AssertException When verify failures are fatal.
     */
    private void fail(String message) throws AssertException {
        if (assertVerifyFails) {
            throw new AssertException(message);
        }
        log.warn("{}, skipping", message);
        skip = true;
    }

    /**
     * Is the response acceptable for assertion.
     *
     * @param httpResponse   HttpResponse instance or null.
     * @param expectedStatus Expected status code, or null for any successful status.
     * @return Boolean.
     */
    private boolean isAcceptable(HttpResponse httpResponse, Long expectedStatus) {
        if (httpResponse == null) {
            return false;
        }
        if (expectedStatus != null) {
            return httpResponse.getStatusCode() == expectedStatus;
        }
        return httpResponse.isSuccessfull();
    }

    /**
     * Collects assertable data from the response.
     *
     * <p>JSON bodies are flattened and filtered by {@code paths} when given; other bodies are split into non-blank lines.
     *
     * @param httpResponse HttpResponse instance.
     * @return List of String.
     */
    @SuppressWarnings("unchecked")
    protected List<String> getData(HttpResponse httpResponse) {
        List<String> data = new ArrayList<>();
        String body = httpResponse.getBody();
        if (body == null || body.isEmpty()) {
            return data;
        }

        String responseCT = httpResponse.getHeaders().get("Content-Type");
        if (responseCT != null && responseCT.toLowerCase().contains("/json")) {
            Map<String, Object> map = new Gson().fromJson(body, Map.class);
            if (map != null && !map.isEmpty()) {
                MapUtils.flattenMap(map, "", data);

                List<String> paths = config.getListProperty("paths");
                if (!paths.isEmpty()) {
                    data = data.stream()
                            .filter(s -> paths.stream().anyMatch(s::startsWith))
                            .collect(Collectors.toList());
                }
            }
        } else {
            for (String line : body.split("\n")) {
                if (!line.isBlank()) {
                    data.add(line);
                }
            }
        }

        return data;
    }

    /**
     * Makes one HTTP request.
     *
     * <p>A response whose Content-Type does not match a requested Content-Type is treated as no response.
     *
     * @return HttpResponse instance or null.
     * @throws AssertException Assertion exception.
     */
    protected HttpResponse makeRequest() throws AssertException {
        try {
            RequestConfig requestConfig = new RequestConfig(config.getMapProperty("request"), connection.getSession());
            HttpResponse httpResponse = new RequestClient(connection.getSession()).request(requestConfig);
            if (httpResponse == null) {
                log.info("AssertExternal request got no response");
                return null;
            }

            String[] configCT = requestConfig.getHeaders().getHeader("Content-Type");
            String responseCT = httpResponse.getHeaders().get("Content-Type");
            if (configCT != null && configCT.length > 0 && (responseCT == null || !responseCT.toLowerCase().startsWith(configCT[0].toLowerCase()))) {
                log.info("AssertExternal request Content-Type did not verify: {} != {}", configCT[0], responseCT);
                return null;
            }

            return httpResponse;

        } catch (Exception e) {
            throw new AssertException(e.getMessage());
        }
    }

    /**
     * HTTP/S request client.
     */
    protected static class RequestClient extends RequestBase {

        /**
         * Constructs a new RequestClient instance with given Session instance.
         *
         * @param session Session instance.
         */
        public RequestClient(Session session) throws Exception {
            super(session);
        }

        /**
         * Make request with given RequestConfig instance.
         *
         * @param config RequestConfig instance.
         * @return HttpResponse instance.
         * @throws AssertException Assertion exception.
         * @throws IOException     Unable to communicate.
         */
        public HttpResponse request(RequestConfig config) throws AssertException, IOException {
            HttpResponse httpResponse = null;
            try {
                httpResponse = makeRequest(config);

            } catch (GeneralSecurityException | IOException e) {
                log.error("Request Client: Request failure: {}", e.getMessage());
            }

            return httpResponse;
        }
    }
}
