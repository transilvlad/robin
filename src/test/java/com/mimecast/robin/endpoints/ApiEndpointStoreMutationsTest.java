package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.mimecast.robin.config.server.EndpointConfig;
import com.mimecast.robin.main.Foundation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ApiEndpointStoreMutationsTest {

    private static final int TEST_PORT = 8094;
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;
    private static final String DOMAIN = "api-mutations.example";
    private static final String USER = "tony";

    private final Gson gson = new Gson();
    private HttpClient httpClient;
    private Path storageRoot;
    private Path domainRoot;
    private Path userRoot;

    @BeforeAll
    void setUp() throws IOException, ConfigurationException {
        Foundation.init("src/test/resources/cfg/");

        ApiEndpoint apiEndpoint = new ApiEndpoint();

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("port", TEST_PORT);
        configMap.put("authType", "none");
        storageRoot = Files.createTempDirectory("robin-store-mutations-");
        configMap.put("storagePath", storageRoot.toString());
        apiEndpoint.start(new EndpointConfig(configMap));

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        httpClient = HttpClient.newHttpClient();
        domainRoot = storageRoot.resolve(DOMAIN);
        userRoot = domainRoot.resolve(USER);
        Files.createDirectories(userRoot);
    }

    @AfterAll
    void tearDown() throws IOException {
        if (storageRoot != null && Files.exists(storageRoot)) {
            try (java.util.stream.Stream<Path> walk = Files.walk(storageRoot)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    @Test
    void testFolderAndProperties() throws Exception {
        HttpResponse<String> create = sendJson("POST", "/store/" + DOMAIN + "/" + USER + "/folders",
                "{\"parent\":\"inbox\",\"name\":\"Projects\"}");
        assertEquals(200, create.statusCode());
        assertTrue(create.body().contains("\"Folder created.\""));
        assertTrue(Files.isDirectory(userRoot.resolve(".Projects").resolve("new")));
        assertTrue(Files.isDirectory(userRoot.resolve(".Projects").resolve("cur")));
        assertTrue(Files.isDirectory(userRoot.resolve(".Projects").resolve("tmp")));
        assertTrue(!Files.exists(userRoot.resolve("inbox")));

        Path eml = userRoot.resolve(".Projects").resolve("new").resolve("msg1.eml");
        Files.createDirectories(eml.getParent());
        Files.writeString(eml, "Subject: one\r\n\r\nBody", StandardCharsets.UTF_8);

        HttpResponse<String> rename = sendJson("PATCH", "/store/" + DOMAIN + "/" + USER + "/folders/.Projects",
                "{\"name\":\"ProjectsRenamed\"}");
        assertEquals(200, rename.statusCode());
        assertTrue(rename.body().contains("\"Folder renamed.\""));

        HttpResponse<String> props = send("GET", "/store/" + DOMAIN + "/" + USER + "/folders/.ProjectsRenamed/properties", null, null);
        assertEquals(200, props.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> propsMap = gson.fromJson(props.body(), Map.class);
        assertEquals(1.0, propsMap.get("r"));
        assertEquals(1.0, propsMap.get("total"));
        assertEquals(1.0, propsMap.get("unread"));

        HttpResponse<String> createParent = sendJson("POST", "/store/" + DOMAIN + "/" + USER + "/folders",
                "{\"name\":\"Trash\"}");
        assertEquals(200, createParent.statusCode());
        HttpResponse<String> createChild = sendJson("POST", "/store/" + DOMAIN + "/" + USER + "/folders",
                "{\"parent\":\".Trash\",\"name\":\"New\"}");
        assertEquals(200, createChild.statusCode());
        assertTrue(Files.isDirectory(userRoot.resolve(".Trash").resolve(".New").resolve("new")));
        assertTrue(Files.isDirectory(userRoot.resolve(".Trash").resolve(".New").resolve("cur")));
        assertTrue(Files.isDirectory(userRoot.resolve(".Trash").resolve(".New").resolve("tmp")));
    }

    @Test
    void testFolderPropertiesUnreadIsScopedToFolder() throws Exception {
        Path inboxNew = userRoot.resolve("new");
        Path trashNew = userRoot.resolve(".Trash").resolve("new");
        Path trashCur = userRoot.resolve(".Trash").resolve("cur");
        Files.createDirectories(inboxNew);
        Files.createDirectories(trashNew);
        Files.createDirectories(trashCur);

        Files.writeString(trashNew.resolve("unread-in-trash.eml"), "Subject: trash\r\n\r\nBody", StandardCharsets.UTF_8);

        HttpResponse<String> inboxProps = send("GET",
                "/store/" + DOMAIN + "/" + USER + "/folders/inbox/properties",
                null, null);
        assertEquals(200, inboxProps.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> inboxMap = gson.fromJson(inboxProps.body(), Map.class);
        assertEquals(0.0, inboxMap.get("unread"));
        assertEquals(0.0, inboxMap.get("total"));

        HttpResponse<String> trashProps = send("GET",
                "/store/" + DOMAIN + "/" + USER + "/folders/.Trash/properties",
                null, null);
        assertEquals(200, trashProps.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> trashMap = gson.fromJson(trashProps.body(), Map.class);
        assertEquals(1.0, trashMap.get("unread"));
        assertEquals(1.0, trashMap.get("total"));
    }

    @Test
    void testMessageOperations() throws Exception {
        Path inboxNew = userRoot.resolve("new");
        Path inboxCur = userRoot.resolve("cur");
        Path trashNew = userRoot.resolve(".Trash").resolve("new");
        Files.createDirectories(inboxNew);
        Files.createDirectories(inboxCur);
        Files.createDirectories(trashNew);

        Path m1 = inboxNew.resolve("a.eml");
        Path m2 = inboxNew.resolve("b.eml");
        Path m3 = inboxNew.resolve("old.eml");
        Files.writeString(m1, "Subject: a\r\n\r\nA", StandardCharsets.UTF_8);
        Files.writeString(m2, "Subject: b\r\n\r\nB", StandardCharsets.UTF_8);
        Files.writeString(m3, "Subject: old\r\n\r\nOld", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(m3, FileTime.from(Instant.now().minus(210, ChronoUnit.DAYS)));

        HttpResponse<String> move = sendJson("POST", "/store/" + DOMAIN + "/" + USER + "/messages/move",
                "{\"fromFolder\":\"inbox\",\"toFolder\":\".Trash\",\"messageIds\":[\"a.eml\"]}");
        assertEquals(200, move.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> moveMap = gson.fromJson(move.body(), Map.class);
        assertTrue(Boolean.TRUE.equals(moveMap.get("success")));
        assertTrue(Files.exists(trashNew.resolve("a.eml")));

        HttpResponse<String> readStatus = sendJson("POST", "/store/" + DOMAIN + "/" + USER + "/messages/read-status",
                "{\"folder\":\"inbox\",\"action\":\"read\",\"messageIds\":[\"b.eml\"]}");
        assertEquals(200, readStatus.statusCode());
        assertTrue(readStatus.body().contains("\"moved\":1"));
        assertTrue(Files.exists(inboxCur.resolve("b.eml")));

        HttpResponse<String> markAllRead = sendJson("POST", "/store/" + DOMAIN + "/" + USER + "/messages/mark-all-read",
                "{\"folder\":\"inbox\"}");
        assertEquals(200, markAllRead.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> markAllMap = gson.fromJson(markAllRead.body(), Map.class);
        assertTrue(Boolean.TRUE.equals(markAllMap.get("success")));
        assertTrue(Files.exists(inboxCur.resolve("old.eml")));

        HttpResponse<String> cleanup = sendJson("POST", "/store/" + DOMAIN + "/" + USER + "/messages/cleanup",
                "{\"folder\":\"inbox\",\"months\":3}");
        assertEquals(200, cleanup.statusCode());
        assertTrue(cleanup.body().contains("\"Cleanup complete.\""));
        assertTrue(!Files.exists(inboxCur.resolve("old.eml")));

        HttpResponse<String> deleteAll = sendJson("POST", "/store/" + DOMAIN + "/" + USER + "/messages/delete-all",
                "{\"folder\":\".Trash\"}");
        assertEquals(200, deleteAll.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> deleteAllMap = gson.fromJson(deleteAll.body(), Map.class);
        assertTrue(Boolean.TRUE.equals(deleteAllMap.get("success")));
        assertTrue(!Files.exists(trashNew.resolve("a.eml")));
    }

    @Test
    void testDraftAndAttachmentLifecycle() throws Exception {
        HttpResponse<String> createDraft = send("POST", "/store/" + DOMAIN + "/" + USER + "/drafts",
                "Subject: Draft\r\n\r\nHello", "message/rfc822");
        assertEquals(200, createDraft.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> createMap = gson.fromJson(createDraft.body(), Map.class);
        String draftId = String.valueOf(createMap.get("draftId"));
        assertTrue(draftId.endsWith(".eml"));

        HttpResponse<String> updateDraft = send("PUT", "/store/" + DOMAIN + "/" + USER + "/drafts/" + draftId,
                "Subject: Draft Updated\r\n\r\nUpdated", "message/rfc822");
        assertEquals(200, updateDraft.statusCode());
        assertTrue(updateDraft.body().contains("\"mail successfully saved.\""));

        HttpResponse<String> addAttachment = send("POST",
                "/store/" + DOMAIN + "/" + USER + "/drafts/" + draftId + "/attachments?filename=file.txt",
                "file-body", "application/octet-stream");
        assertEquals(200, addAttachment.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> attachMap = gson.fromJson(addAttachment.body(), Map.class);
        @SuppressWarnings("unchecked")
        List<String> refs = (List<String>) attachMap.get("f");
        assertEquals(1, refs.size());
        String attachmentId = refs.get(0);

        HttpResponse<String> deleteAttachment = send("DELETE",
                "/store/" + DOMAIN + "/" + USER + "/drafts/" + draftId + "/attachments/" + attachmentId,
                null, null);
        assertEquals(200, deleteAttachment.statusCode());
        assertTrue(deleteAttachment.body().contains("\"r\":1"));

        HttpResponse<String> deleteDraft = send("DELETE", "/store/" + DOMAIN + "/" + USER + "/drafts/" + draftId,
                null, null);
        assertEquals(200, deleteDraft.statusCode());
        assertTrue(deleteDraft.body().contains("\"mail successfully discarded.\""));
    }


    private HttpResponse<String> sendJson(String method, String path, String body) throws Exception {
        return send(method, path, body, "application/json");
    }

    private HttpResponse<String> send(String method, String path, String body, String contentType) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }

        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
