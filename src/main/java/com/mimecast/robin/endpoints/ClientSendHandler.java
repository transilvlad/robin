package com.mimecast.robin.endpoints;

import com.google.gson.Gson;
import com.mimecast.robin.assertion.AssertException;
import com.mimecast.robin.config.client.CaseConfig;
import com.mimecast.robin.main.Client;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.util.Magic;
import com.sun.net.httpserver.HttpExchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for the /client/send endpoint.
 *
 * <p>Accepts JSON/JSON5 case definitions or raw EML uploads and executes
 * the SMTP client immediately, returning the session result.
 *
 * <p>Supports two input modes:
 * <ul>
 *   <li><b>JSON body:</b> Full case definition with envelopes, routing, etc.</li>
 *   <li><b>Raw upload:</b> EML file with mail/rcpt query parameters.</li>
 * </ul>
 */
public class ClientSendHandler implements ApiHandler {
    private static final Logger log = LogManager.getLogger(ClientSendHandler.class);
    private static final String PATH = "/client/send";

    private final HttpEndpoint endpoint;
    private final HttpAuth auth;

    /**
     * Constructs a new ClientSendHandler.
     *
     * @param endpoint The parent HTTP endpoint for response utilities.
     * @param auth     The authentication handler.
     */
    public ClientSendHandler(HttpEndpoint endpoint, HttpAuth auth) {
        this.endpoint = endpoint;
        this.auth = auth;
    }

    @Override
    public String getPath() {
        return PATH;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            log.debug("Rejecting non-POST request to {}: method={}", PATH, exchange.getRequestMethod());
            endpoint.sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        log.info("POST {} from {}", PATH, exchange.getRemoteAddress());
        try {
            if (ApiEndpointUtils.isRawUploadRequest(exchange)) {
                handleRawUpload(exchange);
                return;
            }

            handleJsonBody(exchange);
        } catch (Exception e) {
            log.error("Error processing {}: {}", PATH, e.getMessage());
            endpoint.sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    /**
     * Handles JSON body requests containing case definitions.
     *
     * @param exchange HTTP exchange containing request and response.
     * @throws IOException If an I/O error occurs.
     */
    private void handleJsonBody(HttpExchange exchange) throws IOException {
        String body = ApiEndpointUtils.readBody(exchange.getRequestBody());
        if (body.isBlank()) {
            log.error("{} empty request body", PATH);
            endpoint.sendText(exchange, 400, "Empty request body");
            return;
        }
        log.debug("{} body length: {} bytes", PATH, body.getBytes(StandardCharsets.UTF_8).length);

        String processed = Magic.streamMagicReplace(body);

        @SuppressWarnings("rawtypes")
        Map map = new Gson().fromJson(processed, Map.class);
        if (map == null || map.isEmpty()) {
            log.error("{} invalid JSON body", PATH);
            endpoint.sendText(exchange, 400, "Invalid JSON body");
            return;
        }

        CaseConfig caseConfig = ApiEndpointUtils.buildCaseConfig(map);

        Client client;
        try {
            client = new Client()
                    .setSkip(true)
                    .send(caseConfig);
        } catch (AssertException e) {
            log.error("{} assertion error: {}", PATH, e.getMessage());
            endpoint.sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
            return;
        }

        Session session = client.getSession();
        log.info("{} completed from body: sessionUID={}, envelopes={}",
                PATH, session.getUID(), session.getEnvelopes() != null ? session.getEnvelopes().size() : 0);

        String response = ApiEndpointUtils.getGson().toJson(session);
        endpoint.sendJson(exchange, 200, response);
        log.debug("{} responded 200, bytes={}", PATH, response.getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * Handles raw EML file uploads.
     *
     * @param exchange HTTP exchange containing the upload.
     * @throws IOException If an I/O error occurs.
     */
    private void handleRawUpload(HttpExchange exchange) throws IOException {
        Map<String, String> query = ApiEndpointUtils.parseQuery(exchange.getRequestURI());
        String mail = query.getOrDefault("mail", "").trim();
        List<String> rcpts = ApiEndpointUtils.parseRecipients(query.get("rcpt"));
        if (mail.isBlank() || rcpts.isEmpty()) {
            endpoint.sendText(exchange, 400, "Missing required query parameters: mail and rcpt");
            return;
        }

        byte[] emlBytes = ApiEndpointUtils.readUploadedEmlBytes(exchange);
        if (emlBytes.length == 0) {
            endpoint.sendText(exchange, 400, "Empty upload body");
            return;
        }

        String uploadedPath = ApiEndpointUtils.persistUploadedEml(mail, emlBytes, query.get("filename"));

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("mail", mail);
        envelope.put("rcpt", rcpts);
        envelope.put("file", uploadedPath);

        Map<String, Object> caseMap = new HashMap<>();
        caseMap.put("envelopes", List.of(envelope));

        String route = query.get("route");
        if (route != null && !route.isBlank()) {
            caseMap.put("route", route);
        }

        CaseConfig caseConfig = ApiEndpointUtils.buildCaseConfig(caseMap);
        Client client;
        try {
            client = new Client()
                    .setSkip(true)
                    .send(caseConfig);
        } catch (AssertException e) {
            log.error("Raw upload send assertion error: {}", e.getMessage());
            endpoint.sendText(exchange, 500, "Internal Server Error: " + e.getMessage());
            return;
        }

        Session session = client.getSession();
        log.info("{} raw upload completed: sessionUID={}, uploadedFile={}",
                PATH, session.getUID(), uploadedPath);
        endpoint.sendJson(exchange, 200, ApiEndpointUtils.getGson().toJson(session));
    }
}

