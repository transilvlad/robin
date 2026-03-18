package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.mimecast.robin.config.server.EndpointConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Foundation;
import com.mimecast.robin.storage.rocksdb.InMemoryMailboxStore;
import com.mimecast.robin.storage.rocksdb.MailboxStore;
import com.mimecast.robin.storage.rocksdb.RocksDbMailboxStoreManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "mailbox-store-factory", mode = ResourceAccessMode.READ_WRITE)
class ApiEndpointStoreRocksDbTest {

    private final Gson gson = new Gson();
    private HttpClient client;
    private String baseUrl;
    private ApiEndpoint apiEndpoint;
    private MailboxStore mockStore;

    @BeforeAll
    void setUp() throws IOException, ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
        
        mockStore = new InMemoryMailboxStore("Inbox", "Sent");
        RocksDbMailboxStoreManager.setStoreFactory(() -> mockStore);

        int testPort = findFreePort();
        baseUrl = "http://localhost:" + testPort;

        Map<String, Object> rocksDb = new HashMap<>();
        rocksDb.put("enabled", true);
        rocksDb.put("path", "memory");
        rocksDb.put("inboxFolder", "Inbox");
        rocksDb.put("sentFolder", "Sent");
        Config.getServer().getStorage().getMap().put("rocksdb", rocksDb);

        apiEndpoint = new ApiEndpoint();
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("port", testPort);
        configMap.put("authType", "none");
        apiEndpoint.start(new EndpointConfig(configMap));

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        client = HttpClient.newHttpClient();
    }

    @AfterAll
    void tearDown() throws IOException {
        if (apiEndpoint != null) {
            apiEndpoint.stop();
        }
        RocksDbMailboxStoreManager.closeAll();
        Config.getServer().getStorage().getMap().remove("rocksdb");
    }

    @Test
    void folderAndMessageOperationsWorkOverHttp() throws Exception {
        var first = mockStore.storeInbound("tony@example.com", eml("One"), "one.eml", headers("One"));
        var second = mockStore.storeInbound("tony@example.com", eml("Two"), "two.eml", headers("Two"));

        HttpResponse<String> create = sendJson("POST", "/store-rocksdb/example.com/tony/folders", "{\"name\":\"Projects\"}");
        assertEquals(200, create.statusCode());
        assertTrue(create.body().contains("\"Folder created.\""));

        HttpResponse<String> move = sendJson("POST", "/store-rocksdb/example.com/tony/messages/move",
                "{\"fromFolder\":\"Inbox\",\"toFolder\":\"Projects\",\"messageIds\":[\"" + second.id + "\"]}");
        assertEquals(200, move.statusCode());
        assertTrue(move.body().contains("\"moved\":1"));

        HttpResponse<String> readStatus = sendJson("POST", "/store-rocksdb/example.com/tony/messages/read-status",
                "{\"folder\":\"Projects\",\"action\":\"read\",\"messageIds\":[\"" + second.id + "\"]}");
        assertEquals(200, readStatus.statusCode());

        HttpResponse<String> props = send("GET", "/store-rocksdb/example.com/tony/folders/Projects/properties", null, null);
        assertEquals(200, props.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> propsMap = gson.fromJson(props.body(), Map.class);
        assertEquals(1.0, propsMap.get("total"));
        assertEquals(0.0, propsMap.get("unread"));

        HttpResponse<String> inboxUnread = send("GET", "/store-rocksdb/example.com/tony/folders/Inbox?state=unread", null, null);
        assertEquals(200, inboxUnread.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> inboxMap = gson.fromJson(inboxUnread.body(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inboxMessages = (List<Map<String, Object>>) inboxMap.get("messages");
        assertEquals(1, inboxMessages.size());
        assertEquals(first.id, String.valueOf(inboxMessages.getFirst().get("id")));

        HttpResponse<String> message = send("GET", "/store-rocksdb/example.com/tony/messages/" + first.id, null, null);
        assertEquals(200, message.statusCode());
        assertTrue(message.body().contains("Subject: One"));

        HttpResponse<String> markAllRead = sendJson("POST", "/store-rocksdb/example.com/tony/messages/mark-all-read",
                "{\"folder\":\"Inbox\"}");
        assertEquals(200, markAllRead.statusCode());

        HttpResponse<String> deleteAll = sendJson("POST", "/store-rocksdb/example.com/tony/messages/delete-all",
                "{\"folder\":\"Projects\"}");
        assertEquals(200, deleteAll.statusCode());

        HttpResponse<String> deleteFolder = send("DELETE", "/store-rocksdb/example.com/tony/folders/Projects", null, null);
        assertEquals(200, deleteFolder.statusCode());
    }

    private HttpResponse<String> sendJson(String method, String path, String body) throws Exception {
        return send(method, path, body, "application/json");
    }

    private HttpResponse<String> send(String method, String path, String body, String contentType) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(baseUrl + path));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private byte[] eml(String subject) {
        return ("Subject: " + subject + "\r\nFrom: sender@example.com\r\nTo: tony@example.com\r\n\r\nBody")
                .getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, String> headers(String subject) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Subject", subject);
        headers.put("From", "sender@example.com");
        headers.put("To", "tony@example.com");
        return headers;
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
