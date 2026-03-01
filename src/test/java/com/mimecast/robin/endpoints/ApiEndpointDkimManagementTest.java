package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.mimecast.robin.config.server.EndpointConfig;
import com.mimecast.robin.main.Foundation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ApiEndpointDkimManagementTest {

    private static final int TEST_PORT = 8098;
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;
    private static final String TOKEN = "dkim-test-token";
    private static final String H2_URL = "jdbc:h2:mem:dkim_api_endpoint_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    private final Gson gson = new Gson();
    private HttpClient httpClient;

    @BeforeAll
    void setUp() throws Exception {
        Foundation.init("src/test/resources/cfg/");
        initSchema();

        ApiEndpoint apiEndpoint = new ApiEndpoint();
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("port", TEST_PORT);
        configMap.put("authType", "bearer");
        configMap.put("authValue", TOKEN);
        configMap.put("dkimJdbcUrl", H2_URL);
        configMap.put("dkimJdbcUser", "sa");
        configMap.put("dkimJdbcPassword", "");
        apiEndpoint.start(new EndpointConfig(configMap));

        Thread.sleep(200);
        httpClient = HttpClient.newHttpClient();
    }

    @Test
    void endpointsRequireBearerAuth() throws Exception {
        HttpResponse<String> response = request("GET", "/api/v1/domains/example.com/dkim/keys", null, false);
        assertEquals(401, response.statusCode());
    }

    @Test
    void fullDkimManagementFlowAndNoPrivateKeyExposure() throws Exception {
        HttpResponse<String> generate1 = request("POST", "/api/v1/domains/example.com/dkim/generate",
                """
                {"selector":"s202602a","algorithm":"RSA_2048"}
                """, true);
        assertEquals(201, generate1.statusCode());
        assertNoPrivateKeyLeak(generate1.body());
        long key1 = readId(generate1.body());

        HttpResponse<String> list1 = request("GET", "/api/v1/domains/example.com/dkim/keys", null, true);
        assertEquals(200, list1.statusCode());
        assertNoPrivateKeyLeak(list1.body());

        HttpResponse<String> detail1 = request("GET", "/api/v1/domains/example.com/dkim/keys/" + key1, null, true);
        assertEquals(200, detail1.statusCode());
        assertNoPrivateKeyLeak(detail1.body());

        HttpResponse<String> confirm = request("POST",
                "/api/v1/domains/example.com/dkim/keys/" + key1 + "/confirm-published",
                "{\"prePublishDays\":3}", true);
        assertEquals(200, confirm.statusCode());

        HttpResponse<String> activate = request("POST",
                "/api/v1/domains/example.com/dkim/keys/" + key1 + "/activate",
                "{}", true);
        assertEquals(200, activate.statusCode());

        HttpResponse<String> verify = request("GET",
                "/api/v1/domains/example.com/dkim/keys/" + key1 + "/verify-dns",
                null, true);
        assertEquals(200, verify.statusCode());
        assertTrue(verify.body().contains("recordName"));

        HttpResponse<String> rotate = request("POST",
                "/api/v1/domains/example.com/dkim/rotate",
                "{\"algorithm\":\"ED25519\"}", true);
        assertEquals(200, rotate.statusCode());
        assertNoPrivateKeyLeak(rotate.body());
        long key2 = readId(rotate.body());

        HttpResponse<String> dnsRecords = request("GET",
                "/api/v1/domains/example.com/dkim/dns-records",
                null, true);
        assertEquals(200, dnsRecords.statusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dnsRecordsBody = gson.fromJson(dnsRecords.body(), List.class);
        assertFalse(dnsRecordsBody.isEmpty());

        HttpResponse<String> detected = request("GET",
                "/api/v1/domains/example.com/dkim/detected",
                null, true);
        assertEquals(200, detected.statusCode());

        HttpResponse<String> revoke = request("POST",
                "/api/v1/domains/example.com/dkim/keys/" + key2 + "/revoke",
                "{}", true);
        assertEquals(200, revoke.statusCode());

        HttpResponse<String> generate3 = request("POST", "/api/v1/domains/example.com/dkim/generate",
                """
                {"selector":"s202602b","algorithm":"RSA_2048"}
                """, true);
        assertEquals(201, generate3.statusCode());
        long key3 = readId(generate3.body());

        HttpResponse<String> delete = request("DELETE",
                "/api/v1/domains/example.com/dkim/keys/" + key3,
                null, true);
        assertEquals(200, delete.statusCode());

        HttpResponse<String> retire = request("POST",
                "/api/v1/domains/example.com/dkim/keys/" + key1 + "/retire",
                "{}", true);
        assertEquals(200, retire.statusCode());
    }

    private HttpResponse<String> request(String method, String path, String body, boolean withAuth) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path));

        if (withAuth) {
            builder.header("Authorization", "Bearer " + TOKEN);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private long readId(String json) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = gson.fromJson(json, Map.class);
        assertNotNull(map.get("id"));
        return ((Number) map.get("id")).longValue();
    }

    private void assertNoPrivateKeyLeak(String jsonBody) {
        String lower = jsonBody.toLowerCase();
        assertFalse(lower.contains("privatekeyenc"));
        assertFalse(lower.contains("\"privatekey\""));
        assertFalse(lower.contains("private_key_enc"));
    }

    private void initSchema() throws Exception {
        try (Connection conn = DriverManager.getConnection(H2_URL, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dkim_keys (
                        id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        domain                VARCHAR(253) NOT NULL,
                        selector              VARCHAR(63)  NOT NULL,
                        algorithm             VARCHAR(10)  NOT NULL,
                        private_key_enc       TEXT         NOT NULL,
                        public_key            TEXT         NOT NULL,
                        dns_record_value      TEXT         NOT NULL,
                        status                VARCHAR(20)  NOT NULL,
                        test_mode             BOOLEAN      DEFAULT TRUE,
                        strategy              VARCHAR(20),
                        service_tag           VARCHAR(63),
                        paired_key_id         BIGINT,
                        rotation_scheduled_at TIMESTAMP WITH TIME ZONE,
                        published_at          TIMESTAMP WITH TIME ZONE,
                        activated_at          TIMESTAMP WITH TIME ZONE,
                        retire_after          TIMESTAMP WITH TIME ZONE,
                        retired_at            TIMESTAMP WITH TIME ZONE,
                        created_at            TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT uq_dkim_keys_domain_selector UNIQUE (domain, selector)
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dkim_rotation_events (
                        id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        key_id       BIGINT,
                        event_type   VARCHAR(30) NOT NULL,
                        old_status   VARCHAR(20),
                        new_status   VARCHAR(20),
                        notes        TEXT,
                        triggered_by VARCHAR(50),
                        created_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dkim_detected_selectors (
                        id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        domain       VARCHAR(253) NOT NULL,
                        selector     VARCHAR(63)  NOT NULL,
                        public_key_dns TEXT,
                        algorithm    VARCHAR(10),
                        test_mode    BOOLEAN,
                        revoked      BOOLEAN DEFAULT FALSE,
                        detected_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT uq_dkim_detected_selectors_domain_selector UNIQUE (domain, selector)
                    )
                    """);
        }
    }
}
