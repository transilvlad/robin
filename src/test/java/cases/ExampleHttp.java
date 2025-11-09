package cases;

import com.mimecast.robin.assertion.AssertException;
import com.mimecast.robin.config.client.CaseConfig;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.main.RequestClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class ExampleHttp {

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("cfg/");
    }

    /**
     * JSON example of a test with a DELETE request.
     * <p>Navigate to <a href="https://robin.requestcatcher.com">robin.requestcatcher.com</a> to debug.
     */
    @Test
    void delete() throws Exception {
        new RequestClient()
                .request("src/test/resources/cases/config/request/delete.json5");
    }


    /**
     * JSON example of a test with a POST request with files.
     * <p>Navigate to <a href="https://robin.requestcatcher.com">robin.requestcatcher.com</a> to debug.
     */
    @Test
    void postFiles() throws Exception {
        new RequestClient()
                .request("src/test/resources/cases/config/request/post-files.json5");
    }

    /**
     * JSON example of a test with POST request with JSON.
     * <p>Navigate to <a href="https://robin.requestcatcher.com">robin.requestcatcher.com</a> to debug.
     */
    @Test
    void postJson() throws Exception {
        new RequestClient()
                .request("src/test/resources/cases/config/request/post-json.json5");
    }

    /**
     * JSON example of a test with PUT request with files.
     * <p>Navigate to <a href="https://robin.requestcatcher.com">robin.requestcatcher.com</a> to debug.
     */
    @Test
    void putFiles() throws Exception {
        new RequestClient()
                .request("src/test/resources/cases/config/request/put-files.json5");
    }

    /**
     * JSON example of a test with PUT request with JSON.
     * <p>Navigate to <a href="https://robin.requestcatcher.com">robin.requestcatcher.com</a> to debug.
     */
    @Test
    void putJson() throws Exception {
        new RequestClient()
                .request("src/test/resources/cases/config/request/put-json.json5");
    }

    /**
     * JSON example of a test with GET request.
     * <p>Navigate to <a href="https://robin.requestcatcher.com">robin.requestcatcher.com</a> to debug.
     */
    @Test
    void get() throws Exception {
        new RequestClient()
                .request("src/test/resources/cases/config/request/get.json5");
    }

    /**
     * JSON example of a test with POST request with a map input.
     * <p>Navigate to <a href="https://robin.requestcatcher.com">robin.requestcatcher.com</a> to debug.
     */
    @Test
    void postJsonByMap() throws Exception {
        var map = Map.of(
                "request", Map.of(
                        "type", "POST",
                        "url", "https://robin.requestcatcher.com/api/9745-xxxx",
                        "headers", List.of("Cache-Control", "no-cache"),
                        "content", Map.of(
                                "payload", "{\"name\": \"Robin\"}",
                                "mimeType", "application/json"
                        )
                )
        );
        new RequestClient()
                .request(new CaseConfig(map));
    }
}
