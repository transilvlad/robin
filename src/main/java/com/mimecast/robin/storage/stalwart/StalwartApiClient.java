package com.mimecast.robin.storage.stalwart;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mimecast.robin.config.StalwartConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Factories;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Stalwart HTTP/JMAP client used by Robin direct mailbox delivery.
 */
public class StalwartApiClient {
    private static final Logger log = LogManager.getLogger(StalwartApiClient.class);
    private static final MediaType RFC822 = MediaType.get("message/rfc822");
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Gson GSON = new Gson();
    private static final String JMAP_CORE = "urn:ietf:params:jmap:core";
    private static final String JMAP_MAIL = "urn:ietf:params:jmap:mail";
    private static final String JMAP_PRINCIPALS = "urn:ietf:params:jmap:principals";

    private static volatile StalwartApiClient shared;
    private static volatile SharedClientKey sharedKey;

    private final StalwartConfig config;
    private final OkHttpClient httpClient;
    private final String authHeader;
    private final Semaphore requestLimiter;
    private final ConcurrentHashMap<String, CachedAccount> recipientCache = new ConcurrentHashMap<>();

    private volatile StalwartSession session;

    public static StalwartApiClient shared() {
        return shared(Config.getServer().getStalwart(), Config.getServer().isAllowSelfSigned());
    }

    static StalwartApiClient shared(StalwartConfig config, boolean allowSelfSigned) {
        SharedClientKey desiredKey = SharedClientKey.from(config, allowSelfSigned);
        StalwartApiClient current = shared;
        if (current != null && desiredKey.equals(sharedKey)) {
            return current;
        }

        synchronized (StalwartApiClient.class) {
            current = shared;
            if (current == null || !desiredKey.equals(sharedKey)) {
                current = new StalwartApiClient(config, allowSelfSigned);
                shared = current;
                sharedKey = desiredKey;
            }
        }

        return current;
    }

    public StalwartApiClient(StalwartConfig config) {
        this(config, Config.getServer().isAllowSelfSigned());
    }

    StalwartApiClient(StalwartConfig config, boolean allowSelfSigned) {
        this.config = Objects.requireNonNull(config, "config");
        this.authHeader = Credentials.basic(config.getUsername(), config.getPassword(), StandardCharsets.UTF_8);
        this.requestLimiter = new Semaphore(Math.max(1, config.getMaxConcurrentRequests()));
        this.httpClient = buildClient(config, allowSelfSigned);
    }

    static synchronized void resetSharedForTest() {
        shared = null;
        sharedKey = null;
    }

    public Map<String, String> deliverToRecipients(byte[] rawMessage, Collection<String> recipients) throws IOException {
        ensureConfigured();
        StalwartSession currentSession = discoverSession();
        Map<String, String> failures = new LinkedHashMap<>();
        Map<String, ResolvedAccount> accountsById = new LinkedHashMap<>();
        Map<String, List<String>> recipientsByAccount = new LinkedHashMap<>();

        for (String recipient : new LinkedHashSet<>(recipients)) {
            if (recipient == null || recipient.isBlank()) {
                continue;
            }

            try {
                ResolvedAccount account = resolveAccount(currentSession, recipient);
                accountsById.putIfAbsent(account.accountId(), account);
                recipientsByAccount.computeIfAbsent(account.accountId(), key -> new ArrayList<>()).add(recipient);
            } catch (IOException e) {
                failures.put(recipient, e.getMessage());
            }
        }

        for (Map.Entry<String, List<String>> entry : recipientsByAccount.entrySet()) {
            ResolvedAccount account = accountsById.get(entry.getKey());
            try {
                String blobId = uploadMessage(currentSession, account.accountId(), rawMessage);
                importToInbox(currentSession, account, blobId);
            } catch (IOException e) {
                log.warn("Stalwart direct import failed for account={} recipients={}: {}",
                        account.accountId(), String.join(",", entry.getValue()), e.getMessage());
                for (String recipient : entry.getValue()) {
                    failures.put(recipient, e.getMessage());
                }
            }
        }

        return failures;
    }

    private ResolvedAccount resolveAccount(StalwartSession currentSession, String recipient) throws IOException {
        String normalized = recipient.trim().toLowerCase();
        CachedAccount cached = recipientCache.get(normalized);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.account();
        }

        String accountId = queryPrincipalAccountId(currentSession, normalized);
        String inboxMailboxId = configuredInboxMailboxId();
        if (inboxMailboxId == null) {
            inboxMailboxId = queryInboxMailboxId(currentSession, accountId);
        }
        ResolvedAccount account = new ResolvedAccount(accountId, normalized, inboxMailboxId);

        evictExpiredCacheEntries(now);
        recipientCache.put(normalized,
                new CachedAccount(account, now + TimeUnit.SECONDS.toMillis(config.getLookupCacheTtlSeconds())));
        return account;
    }

    private String queryPrincipalAccountId(StalwartSession currentSession, String normalizedRecipient) throws IOException {
        JsonObject requestBody = new JsonObject();
        requestBody.add("using", capabilities(JMAP_CORE, JMAP_PRINCIPALS));

        JsonArray methodCalls = new JsonArray();
        JsonArray queryCall = new JsonArray();
        queryCall.add("Principal/query");

        JsonObject queryArgs = new JsonObject();
        JsonObject filter = new JsonObject();
        filter.addProperty("email", normalizedRecipient);
        queryArgs.add("filter", filter);
        queryCall.add(queryArgs);
        queryCall.add("principal-query");
        methodCalls.add(queryCall);
        requestBody.add("methodCalls", methodCalls);

        JsonObject responseBody = postJson(currentSession.apiUrl(), requestBody, "query Stalwart principal " + normalizedRecipient);
        JsonArray methodResponses = responseBody.getAsJsonArray("methodResponses");
        if (methodResponses == null || methodResponses.isEmpty()) {
            throw new IOException("Stalwart Principal/query returned no methodResponses");
        }

        JsonArray methodResponse = methodResponses.get(0).getAsJsonArray();
        if (methodResponse.size() < 2 || !"Principal/query".equals(methodResponse.get(0).getAsString())) {
            throw new IOException("Unexpected Stalwart response for Principal/query");
        }

        JsonObject queryResponse = methodResponse.get(1).getAsJsonObject();
        JsonArray ids = queryResponse.getAsJsonArray("ids");
        if (ids == null || ids.isEmpty()) {
            throw new IOException("Stalwart principal not found for " + normalizedRecipient);
        }

        return ids.get(0).getAsString();
    }

    private String queryInboxMailboxId(StalwartSession currentSession, String accountId) throws IOException {
        JsonObject requestBody = new JsonObject();
        requestBody.add("using", capabilities(JMAP_CORE, JMAP_MAIL));

        JsonArray methodCalls = new JsonArray();
        JsonArray queryCall = new JsonArray();
        queryCall.add("Mailbox/query");

        JsonObject queryArgs = new JsonObject();
        queryArgs.addProperty("accountId", accountId);
        JsonObject filter = new JsonObject();
        filter.addProperty("role", "inbox");
        queryArgs.add("filter", filter);
        queryCall.add(queryArgs);
        queryCall.add("mailbox-query");
        methodCalls.add(queryCall);
        requestBody.add("methodCalls", methodCalls);

        JsonObject responseBody = postJson(currentSession.apiUrl(), requestBody, "query Inbox mailbox for Stalwart account " + accountId);
        JsonArray methodResponses = responseBody.getAsJsonArray("methodResponses");
        if (methodResponses == null || methodResponses.isEmpty()) {
            throw new IOException("Stalwart Mailbox/query returned no methodResponses");
        }

        JsonArray methodResponse = methodResponses.get(0).getAsJsonArray();
        if (methodResponse.size() < 2 || !"Mailbox/query".equals(methodResponse.get(0).getAsString())) {
            throw new IOException("Unexpected Stalwart response for Mailbox/query");
        }

        JsonObject queryResponse = methodResponse.get(1).getAsJsonObject();
        JsonArray ids = queryResponse.getAsJsonArray("ids");
        if (ids == null || ids.isEmpty()) {
            throw new IOException("Inbox mailbox not found for Stalwart account " + accountId);
        }

        return ids.get(0).getAsString();
    }

    private String uploadMessage(StalwartSession currentSession, String accountId, byte[] rawMessage) throws IOException {
        String uploadUrl = currentSession.uploadUrlTemplate().replace("{accountId}", accountId);
        Request request = new Request.Builder()
                .url(uploadUrl)
                .header("Authorization", authHeader)
                .post(RequestBody.create(rawMessage, RFC822))
                .build();

        JsonObject responseBody = executeJson(request, "upload message blob to Stalwart account " + accountId);
        if (!responseBody.has("blobId") || responseBody.get("blobId").isJsonNull()) {
            throw new IOException("Stalwart upload did not return blobId");
        }
        return responseBody.get("blobId").getAsString();
    }

    private void importToInbox(StalwartSession currentSession, ResolvedAccount account, String blobId) throws IOException {
        JsonObject requestBody = new JsonObject();
        requestBody.add("using", capabilities(JMAP_CORE, JMAP_MAIL));

        JsonArray methodCalls = new JsonArray();
        JsonArray importCall = new JsonArray();
        importCall.add("Email/import");

        JsonObject importArgs = new JsonObject();
        importArgs.addProperty("accountId", account.accountId());
        JsonObject emails = new JsonObject();
        JsonObject importObject = new JsonObject();
        importObject.addProperty("blobId", blobId);
        JsonObject mailboxIds = new JsonObject();
        mailboxIds.addProperty(account.inboxMailboxId(), true);
        importObject.add("mailboxIds", mailboxIds);
        emails.add("import-1", importObject);
        importArgs.add("emails", emails);

        importCall.add(importArgs);
        importCall.add("email-import");
        methodCalls.add(importCall);
        requestBody.add("methodCalls", methodCalls);

        JsonObject responseBody = postJson(currentSession.apiUrl(), requestBody,
                "import message into Stalwart account " + account.accountId());
        JsonArray methodResponses = responseBody.getAsJsonArray("methodResponses");
        if (methodResponses == null || methodResponses.isEmpty()) {
            throw new IOException("Stalwart JMAP import returned no methodResponses");
        }

        JsonArray methodResponse = methodResponses.get(0).getAsJsonArray();
        if (methodResponse.size() < 2 || !"Email/import".equals(methodResponse.get(0).getAsString())) {
            throw new IOException("Unexpected Stalwart JMAP response for Email/import");
        }

        JsonObject importResponse = methodResponse.get(1).getAsJsonObject();
        JsonObject notCreated = importResponse.getAsJsonObject("notCreated");
        if (notCreated != null && !notCreated.entrySet().isEmpty()) {
            JsonObject firstError = notCreated.entrySet().iterator().next().getValue().getAsJsonObject();
            String type = firstError.has("type") ? firstError.get("type").getAsString() : "unknown";
            String description = firstError.has("description") ? firstError.get("description").getAsString() : "unknown import error";
            throw new IOException("Stalwart import failed: " + type + ": " + description);
        }
    }

    private StalwartSession discoverSession() throws IOException {
        StalwartSession current = session;
        if (current != null) {
            return current;
        }

        Request request = new Request.Builder()
                .url(joinUrl(config.getBaseUrl(), "/jmap/session"))
                .header("Authorization", authHeader)
                .get()
                .build();
        JsonObject responseBody = executeJson(request, "load Stalwart JMAP session");

        String apiUrl = rebaseUrl(stringProperty(responseBody, "apiUrl"), config.getBaseUrl());
        String uploadUrl = rebaseUrl(stringProperty(responseBody, "uploadUrl"), config.getBaseUrl());
        current = new StalwartSession(apiUrl, uploadUrl);
        session = current;
        return current;
    }

    private JsonObject postJson(String url, JsonObject body, String action) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .post(RequestBody.create(GSON.toJson(body), JSON))
                .build();
        return executeJson(request, action);
    }

    private JsonObject executeJson(Request request, String action) throws IOException {
        acquirePermit(action);
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "{}";
            if (!response.isSuccessful()) {
                throw new IOException("Failed to " + action + ": HTTP " + response.code() + " " + body);
            }
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } finally {
            requestLimiter.release();
        }
    }

    private void acquirePermit(String action) throws IOException {
        try {
            if (!requestLimiter.tryAcquire(config.getReadTimeoutSeconds(), TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting to " + action);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to " + action, e);
        }
    }

    private void ensureConfigured() throws IOException {
        if (!config.isEnabled()) {
            throw new IOException("Stalwart direct ingest is disabled");
        }
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IOException("Stalwart baseUrl is not configured");
        }
        if (config.getUsername() == null || config.getUsername().isBlank()) {
            throw new IOException("Stalwart username is not configured");
        }
        if (config.getPassword() == null || config.getPassword().isBlank()) {
            throw new IOException("Stalwart password is not configured");
        }
    }

    private void evictExpiredCacheEntries(long now) {
        recipientCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        int maxEntries = Math.max(1, config.getLookupCacheMaxEntries());
        if (recipientCache.size() <= maxEntries) {
            return;
        }
        int toRemove = recipientCache.size() - maxEntries;
        for (String key : new ArrayList<>(recipientCache.keySet())) {
            recipientCache.remove(key);
            if (--toRemove <= 0) {
                break;
            }
        }
    }

    private String configuredInboxMailboxId() {
        String mailboxId = config.getInboxMailboxId();
        if (mailboxId == null) {
            return null;
        }
        mailboxId = mailboxId.trim();
        return mailboxId.isEmpty() ? null : mailboxId;
    }

    private static JsonArray capabilities(String... values) {
        JsonArray capabilities = new JsonArray();
        for (String value : values) {
            capabilities.add(value);
        }
        return capabilities;
    }

    private static String stringProperty(JsonObject object, String property) throws IOException {
        if (!object.has(property) || object.get(property).isJsonNull()) {
            throw new IOException("Missing Stalwart session property: " + property);
        }
        return object.get(property).getAsString();
    }

    private static String rebaseUrl(String discoveredUrl, String baseUrl) {
        String trimmedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (discoveredUrl.startsWith("http://") || discoveredUrl.startsWith("https://")) {
            int schemeIndex = discoveredUrl.indexOf("://");
            int pathIndex = discoveredUrl.indexOf('/', schemeIndex + 3);
            return pathIndex >= 0 ? trimmedBase + discoveredUrl.substring(pathIndex) : trimmedBase;
        }
        if (discoveredUrl.startsWith("/")) {
            return trimmedBase + discoveredUrl;
        }
        return trimmedBase + "/" + discoveredUrl;
    }

    private static OkHttpClient buildClient(StalwartConfig config, boolean allowSelfSigned) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(config.getWriteTimeoutSeconds(), TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true);

        if (allowSelfSigned) {
            try {
                X509TrustManager trustManager = Factories.getTrustManager();
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new javax.net.ssl.TrustManager[]{trustManager}, null);
                builder.sslSocketFactory(sslContext.getSocketFactory(), trustManager);
                builder.hostnameVerifier((hostname, session) -> true);
            } catch (Exception e) {
                log.warn("Failed to configure permissive TLS for Stalwart direct ingest: {}", e.getMessage());
            }
        }

        return builder.build();
    }

    private static String joinUrl(String baseUrl, String path) {
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + path;
        }
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        return baseUrl + path;
    }

    private record StalwartSession(String apiUrl, String uploadUrlTemplate) {
    }

    private record ResolvedAccount(String accountId, String email, String inboxMailboxId) {
    }

    private record CachedAccount(ResolvedAccount account, long expiresAtMillis) {
    }

    private record SharedClientKey(
            boolean enabled,
            String baseUrl,
            String username,
            String password,
            long connectTimeoutSeconds,
            long readTimeoutSeconds,
            long writeTimeoutSeconds,
            int lookupCacheTtlSeconds,
            int lookupCacheMaxEntries,
            int maxConcurrentRequests,
            String inboxMailboxId,
            boolean allowSelfSigned
    ) {
        private static SharedClientKey from(StalwartConfig config, boolean allowSelfSigned) {
            return new SharedClientKey(
                    config.isEnabled(),
                    config.getBaseUrl(),
                    config.getUsername(),
                    config.getPassword(),
                    config.getConnectTimeoutSeconds(),
                    config.getReadTimeoutSeconds(),
                    config.getWriteTimeoutSeconds(),
                    config.getLookupCacheTtlSeconds(),
                    config.getLookupCacheMaxEntries(),
                    config.getMaxConcurrentRequests(),
                    config.getInboxMailboxId(),
                    allowSelfSigned
            );
        }
    }
}
