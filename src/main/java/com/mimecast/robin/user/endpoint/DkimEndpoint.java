package com.mimecast.robin.user.endpoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.mimecast.robin.endpoints.HttpAuth;
import com.mimecast.robin.user.domain.DkimDetectedSelector;
import com.mimecast.robin.user.domain.DkimKey;
import com.mimecast.robin.user.domain.DkimKeyStatus;
import com.mimecast.robin.user.domain.DkimRotationEvent;
import com.mimecast.robin.user.domain.DkimStrategy;
import com.mimecast.robin.user.endpoint.dto.DkimDnsRecordDto;
import com.mimecast.robin.user.endpoint.dto.DkimGenerateRequest;
import com.mimecast.robin.user.endpoint.dto.DkimKeyDto;
import com.mimecast.robin.user.repository.DkimKeyRepository;
import com.mimecast.robin.user.service.DkimDnsRecordBuilder;
import com.mimecast.robin.user.service.DkimKeyGenerator;
import com.mimecast.robin.user.service.DkimLifecycleService;
import com.mimecast.robin.user.service.DkimRotationService;
import com.sun.net.httpserver.HttpExchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * REST handler for DKIM key lifecycle APIs.
 */
public class DkimEndpoint {
    private static final Logger log = LogManager.getLogger(DkimEndpoint.class);

    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$"
    );
    private static final Pattern SELECTOR_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,62}$");

    private final HttpAuth auth;
    private final Supplier<DkimKeyRepository> repositorySupplier;
    private final Gson gson;
    private final DkimKeyGenerator keyGenerator;
    private final DkimDnsRecordBuilder dnsRecordBuilder;

    public DkimEndpoint(HttpAuth auth, Supplier<DkimKeyRepository> repositorySupplier) {
        this.auth = Objects.requireNonNull(auth, "auth");
        this.repositorySupplier = Objects.requireNonNull(repositorySupplier, "repositorySupplier");
        this.gson = new GsonBuilder().create();
        this.keyGenerator = new DkimKeyGenerator();
        this.dnsRecordBuilder = new DkimDnsRecordBuilder();
    }

    /**
     * Main request handler mounted at /domains and /api/v1/domains.
     */
    public void handle(HttpExchange exchange) throws IOException {
        if (!auth.isAuthenticated(exchange)) {
            auth.sendAuthRequired(exchange);
            return;
        }

        try {
            String path = exchange.getRequestURI().getPath();
            String suffix = trimBase(path);
            if (suffix == null) {
                sendJson(exchange, 404, Map.of("error", "Not Found"));
                return;
            }

            List<String> parts = splitPath(suffix);
            if (parts.size() < 2 || !"dkim".equals(parts.get(1))) {
                sendJson(exchange, 404, Map.of("error", "Not Found"));
                return;
            }

            String domainToken = decode(parts.get(0));

            if (parts.size() == 3 && "keys".equals(parts.get(2))) {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleListKeys(exchange, domainToken);
                    return;
                }
                sendJson(exchange, 405, Map.of("error", "Method Not Allowed"));
                return;
            }

            if (parts.size() == 3 && "generate".equals(parts.get(2))) {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleGenerate(exchange, domainToken);
                    return;
                }
                sendJson(exchange, 405, Map.of("error", "Method Not Allowed"));
                return;
            }

            if (parts.size() == 3 && "rotate".equals(parts.get(2))) {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleRotate(exchange, domainToken);
                    return;
                }
                sendJson(exchange, 405, Map.of("error", "Method Not Allowed"));
                return;
            }

            if (parts.size() == 3 && "detected".equals(parts.get(2))) {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleDetected(exchange, domainToken);
                    return;
                }
                sendJson(exchange, 405, Map.of("error", "Method Not Allowed"));
                return;
            }

            if (parts.size() == 3 && "dns-records".equals(parts.get(2))) {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleDnsRecords(exchange, domainToken);
                    return;
                }
                sendJson(exchange, 405, Map.of("error", "Method Not Allowed"));
                return;
            }

            if (parts.size() >= 4 && "keys".equals(parts.get(2))) {
                long keyId = parseKeyId(parts.get(3));
                if (keyId <= 0) {
                    sendJson(exchange, 400, Map.of("error", "Invalid key id"));
                    return;
                }

                if (parts.size() == 4) {
                    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleKeyDetail(exchange, domainToken, keyId);
                        return;
                    }
                    if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleForceRetire(exchange, domainToken, keyId);
                        return;
                    }
                    sendJson(exchange, 405, Map.of("error", "Method Not Allowed"));
                    return;
                }

                String action = parts.get(4);
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())
                        && !("verify-dns".equals(action) && "GET".equalsIgnoreCase(exchange.getRequestMethod()))) {
                    sendJson(exchange, 405, Map.of("error", "Method Not Allowed"));
                    return;
                }

                switch (action) {
                    case "confirm-published" -> handleConfirmPublished(exchange, domainToken, keyId);
                    case "activate" -> handleActivate(exchange, domainToken, keyId);
                    case "retire" -> handleForceRetire(exchange, domainToken, keyId);
                    case "revoke" -> handleRevoke(exchange, domainToken, keyId);
                    case "verify-dns" -> handleVerifyDns(exchange, domainToken, keyId);
                    default -> sendJson(exchange, 404, Map.of("error", "Not Found"));
                }
                return;
            }

            sendJson(exchange, 404, Map.of("error", "Not Found"));
        } catch (Exception e) {
            log.error("DKIM endpoint failure: {}", e.getMessage());
            sendJson(exchange, 500, Map.of("error", "Internal Server Error", "message", e.getMessage()));
        }
    }

    private void handleListKeys(HttpExchange exchange, String domainToken) throws IOException {
        DkimKeyRepository repository = repository();
        List<DkimKeyDto> keys = repository.findByDomain(domainToken).stream()
                .map(this::toDto)
                .toList();
        sendJson(exchange, 200, keys);
    }

    private void handleGenerate(HttpExchange exchange, String domainToken) throws IOException {
        DkimGenerateRequest req = parseBody(exchange, DkimGenerateRequest.class);
        String domain = resolveDomain(domainToken, req != null ? req.getDomain() : null);
        if (domain == null || domain.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "Missing domain"));
            return;
        }

        String selector = req != null ? trim(req.getSelector()) : "";
        DkimKeyRepository repository = repository();
        if (selector.isBlank()) {
            selector = nextSelector(domain, repository);
        }
        if (!isValidSelector(selector)) {
            sendJson(exchange, 400, Map.of("error", "Invalid selector"));
            return;
        }

        String algorithm = normalizeAlgorithm(req != null ? req.getAlgorithm() : null);
        if (algorithm == null) {
            sendJson(exchange, 400, Map.of("error", "Unsupported algorithm"));
            return;
        }

        DkimStrategy strategy = parseStrategy(req != null ? req.getStrategy() : null);
        boolean testMode = req == null || req.getTestMode() == null || req.getTestMode();
        String serviceTag = req != null ? trim(req.getServiceTag()) : null;

        DkimKeyGenerator.GeneratedKey generated = "ED25519".equals(algorithm)
                ? keyGenerator.generateEd25519()
                : keyGenerator.generateRsa2048();

        DkimKey key = new DkimKey();
        key.setDomain(domain);
        key.setSelector(selector);
        key.setAlgorithm(algorithm);
        key.setPrivateKeyEnc(generated.privateKeyPem());
        key.setPublicKey(generated.publicKeyPem());
        key.setStatus(DkimKeyStatus.PENDING_PUBLISH);
        key.setTestMode(testMode);
        key.setStrategy(strategy);
        key.setServiceTag(serviceTag);
        key.setDnsRecordValue(dnsRecordBuilder.buildTxtValue(algorithm, generated.dnsPublicKey(), testMode));

        repository.save(key);
        logEvent(repository, key.getId(), "GENERATED", null, DkimKeyStatus.PENDING_PUBLISH,
                "Generated via API", "USER");
        sendJson(exchange, 201, toDto(key));
    }

    private void handleKeyDetail(HttpExchange exchange, String domainToken, long keyId) throws IOException {
        DkimKeyRepository repository = repository();
        Optional<DkimKey> key = repository.findById(keyId);
        if (key.isEmpty() || !domainMatches(domainToken, key.get().getDomain())) {
            sendJson(exchange, 404, Map.of("error", "Key not found"));
            return;
        }
        sendJson(exchange, 200, toDto(key.get()));
    }

    private void handleConfirmPublished(HttpExchange exchange, String domainToken, long keyId) throws IOException {
        DkimKeyRepository repository = repository();
        Optional<DkimKey> key = repository.findById(keyId);
        if (key.isEmpty() || !domainMatches(domainToken, key.get().getDomain())) {
            sendJson(exchange, 404, Map.of("error", "Key not found"));
            return;
        }

        int prePublishDays = 7;
        Map<?, ?> body = parseBody(exchange, Map.class);
        if (body != null && body.get("prePublishDays") != null) {
            try {
                prePublishDays = Math.max(0, Integer.parseInt(String.valueOf(body.get("prePublishDays"))));
            } catch (NumberFormatException ignore) {
                // keep default
            }
        }

        DkimRotationService rotationService = new DkimRotationService(repository, new DkimLifecycleService(repository));
        DkimKey updated = rotationService.confirmPublished(keyId, "USER", "Confirmed via API", prePublishDays);
        sendJson(exchange, 200, toDto(updated));
    }

    private void handleActivate(HttpExchange exchange, String domainToken, long keyId) throws IOException {
        DkimKeyRepository repository = repository();
        Optional<DkimKey> key = repository.findById(keyId);
        if (key.isEmpty() || !domainMatches(domainToken, key.get().getDomain())) {
            sendJson(exchange, 404, Map.of("error", "Key not found"));
            return;
        }

        DkimLifecycleService lifecycle = new DkimLifecycleService(repository);
        DkimKey updated = lifecycle.activate(keyId, "USER", "Activated via API");
        sendJson(exchange, 200, toDto(updated));
    }

    private void handleForceRetire(HttpExchange exchange, String domainToken, long keyId) throws IOException {
        DkimKeyRepository repository = repository();
        Optional<DkimKey> keyOpt = repository.findById(keyId);
        if (keyOpt.isEmpty() || !domainMatches(domainToken, keyOpt.get().getDomain())) {
            sendJson(exchange, 404, Map.of("error", "Key not found"));
            return;
        }

        DkimKey key = keyOpt.get();
        DkimKeyStatus oldStatus = key.getStatus();
        key.setRetireAfter(OffsetDateTime.now().minusSeconds(1));
        key.setRetiredAt(OffsetDateTime.now());
        key.setStatus(DkimKeyStatus.RETIRED);
        repository.save(key);
        logEvent(repository, key.getId(), "RETIRED", oldStatus, DkimKeyStatus.RETIRED,
                "Force-retired via API", "USER");
        sendJson(exchange, 200, toDto(key));
    }

    private void handleRevoke(HttpExchange exchange, String domainToken, long keyId) throws IOException {
        DkimKeyRepository repository = repository();
        Optional<DkimKey> key = repository.findById(keyId);
        if (key.isEmpty() || !domainMatches(domainToken, key.get().getDomain())) {
            sendJson(exchange, 404, Map.of("error", "Key not found"));
            return;
        }

        DkimLifecycleService lifecycle = new DkimLifecycleService(repository);
        DkimKey updated = lifecycle.transition(keyId, DkimKeyStatus.REVOKED, "USER", "Revoked via API");
        sendJson(exchange, 200, toDto(updated));
    }

    private void handleVerifyDns(HttpExchange exchange, String domainToken, long keyId) throws IOException {
        DkimKeyRepository repository = repository();
        Optional<DkimKey> keyOpt = repository.findById(keyId);
        if (keyOpt.isEmpty() || !domainMatches(domainToken, keyOpt.get().getDomain())) {
            sendJson(exchange, 404, Map.of("error", "Key not found"));
            return;
        }

        DkimKey key = keyOpt.get();
        String recordName = key.getSelector() + "._domainkey." + key.getDomain();
        List<String> answers = new ArrayList<>();
        try {
            Lookup lookup = new Lookup(recordName, Type.TXT);
            Record[] records = lookup.run();
            if (records != null) {
                for (Record record : records) {
                    if (record instanceof TXTRecord txtRecord) {
                        answers.add(String.join("", txtRecord.getStrings()));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("verify-dns failed for {}: {}", recordName, e.getMessage());
        }

        String expectedP = parseTags(key.getDnsRecordValue()).getOrDefault("p", "");
        boolean published = !answers.isEmpty();
        boolean revoked = false;
        boolean matches = false;
        if (!answers.isEmpty()) {
            Map<String, String> tags = parseTags(answers.get(0));
            String resolvedP = tags.getOrDefault("p", "");
            revoked = tags.containsKey("p") && resolvedP.isEmpty();
            matches = Objects.equals(expectedP, resolvedP);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("recordName", recordName);
        response.put("published", published);
        response.put("matches", matches);
        response.put("revoked", revoked);
        response.put("answers", answers);
        sendJson(exchange, 200, response);
    }

    private void handleDetected(HttpExchange exchange, String domainToken) throws IOException {
        DkimKeyRepository repository = repository();
        List<DkimDetectedSelector> selectors = repository.findDetectedSelectorsByDomain(domainToken);
        sendJson(exchange, 200, selectors);
    }

    private void handleRotate(HttpExchange exchange, String domainToken) throws IOException {
        DkimGenerateRequest req = parseBody(exchange, DkimGenerateRequest.class);
        DkimKeyRepository repository = repository();
        String domain = resolveDomain(domainToken, req != null ? req.getDomain() : null);
        if (domain == null || domain.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "Missing domain"));
            return;
        }

        String selector = req != null ? trim(req.getSelector()) : "";
        if (selector.isBlank()) {
            selector = nextSelector(domain, repository);
        }
        if (!isValidSelector(selector)) {
            sendJson(exchange, 400, Map.of("error", "Invalid selector"));
            return;
        }

        String algorithm = normalizeAlgorithm(req != null ? req.getAlgorithm() : null);
        if (algorithm == null) {
            sendJson(exchange, 400, Map.of("error", "Unsupported algorithm"));
            return;
        }

        DkimStrategy strategy = parseStrategy(req != null ? req.getStrategy() : null);
        boolean testMode = req == null || req.getTestMode() == null || req.getTestMode();
        String serviceTag = req != null ? trim(req.getServiceTag()) : null;

        DkimKeyGenerator.GeneratedKey generated = "ED25519".equals(algorithm)
                ? keyGenerator.generateEd25519()
                : keyGenerator.generateRsa2048();

        DkimKey key = new DkimKey();
        key.setDomain(domain);
        key.setSelector(selector);
        key.setAlgorithm(algorithm);
        key.setPrivateKeyEnc(generated.privateKeyPem());
        key.setPublicKey(generated.publicKeyPem());
        key.setStatus(DkimKeyStatus.PENDING_PUBLISH);
        key.setTestMode(testMode);
        key.setStrategy(strategy);
        key.setServiceTag(serviceTag);
        key.setDnsRecordValue(dnsRecordBuilder.buildTxtValue(algorithm, generated.dnsPublicKey(), testMode));
        repository.save(key);
        logEvent(repository, key.getId(), "GENERATED", null, DkimKeyStatus.PENDING_PUBLISH,
                "Generated via rotate API", "USER");

        DkimLifecycleService lifecycle = new DkimLifecycleService(repository);
        DkimKey activated = lifecycle.activate(key.getId(), "USER", "Rotated via API");
        sendJson(exchange, 200, toDto(activated));
    }

    private void handleDnsRecords(HttpExchange exchange, String domainToken) throws IOException {
        DkimKeyRepository repository = repository();
        List<DkimDnsRecordDto> records = repository.findByDomain(domainToken).stream()
                .map(this::toDnsRecordDto)
                .toList();
        sendJson(exchange, 200, records);
    }

    private String resolveDomain(String domainToken, String bodyDomain) {
        String candidate = trim(bodyDomain);
        if (!candidate.isBlank() && isValidDomain(candidate)) {
            return candidate.toLowerCase(Locale.ROOT);
        }
        return trim(domainToken).toLowerCase(Locale.ROOT);
    }

    private String nextSelector(String domain, DkimKeyRepository repository) {
        String base = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        List<String> existing = repository.findByDomain(domain).stream()
                .map(DkimKey::getSelector)
                .map(String::toLowerCase)
                .toList();
        if (!existing.contains(base.toLowerCase(Locale.ROOT))) {
            return base;
        }
        for (char suffix = 'a'; suffix <= 'z'; suffix++) {
            String candidate = base + suffix;
            if (!existing.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return base + System.currentTimeMillis() % 1000;
    }

    private boolean domainMatches(String domainToken, String keyDomain) {
        return keyDomain != null && keyDomain.equalsIgnoreCase(trim(domainToken));
    }

    private DkimKeyDto toDto(DkimKey key) {
        return new DkimKeyDto(
                key.getId(),
                key.getDomain(),
                key.getSelector(),
                key.getAlgorithm(),
                key.getStatus() != null ? key.getStatus().name() : null,
                key.isTestMode(),
                key.getStrategy() != null ? key.getStrategy().name() : null,
                key.getServiceTag(),
                key.getPairedKeyId(),
                key.getPublicKey(),
                toIso(key.getRotationScheduledAt()),
                toIso(key.getPublishedAt()),
                toIso(key.getActivatedAt()),
                toIso(key.getRetireAfter()),
                toIso(key.getRetiredAt()),
                toIso(key.getCreatedAt()),
                toDnsRecordDto(key)
        );
    }

    private DkimDnsRecordDto toDnsRecordDto(DkimKey key) {
        String name = key.getSelector() + "._domainkey." + key.getDomain();
        List<String> chunks = dnsRecordBuilder.chunkTxtValue(key.getDnsRecordValue() == null ? "" : key.getDnsRecordValue());
        return new DkimDnsRecordDto(
                key.getId(),
                name,
                "TXT",
                key.getDnsRecordValue(),
                chunks,
                key.getStatus() != null ? key.getStatus().name() : null
        );
    }

    private void logEvent(DkimKeyRepository repository,
                          Long keyId,
                          String eventType,
                          DkimKeyStatus oldStatus,
                          DkimKeyStatus newStatus,
                          String notes,
                          String triggeredBy) {
        DkimRotationEvent event = new DkimRotationEvent();
        event.setKeyId(keyId);
        event.setEventType(eventType);
        event.setOldStatus(oldStatus != null ? oldStatus.name() : null);
        event.setNewStatus(newStatus != null ? newStatus.name() : null);
        event.setNotes(notes);
        event.setTriggeredBy(triggeredBy);
        repository.logEvent(event);
    }

    private Map<String, String> parseTags(String value) {
        Map<String, String> tags = new HashMap<>();
        if (value == null) {
            return tags;
        }
        for (String part : value.split(";")) {
            int idx = part.indexOf('=');
            if (idx > 0) {
                tags.put(part.substring(0, idx).trim().toLowerCase(Locale.ROOT), part.substring(idx + 1).trim());
            }
        }
        return tags;
    }

    private String trimBase(String fullPath) {
        if (fullPath.startsWith("/api/v1/domains")) {
            return fullPath.substring("/api/v1/domains".length());
        }
        if (fullPath.startsWith("/domains")) {
            return fullPath.substring("/domains".length());
        }
        return null;
    }

    private List<String> splitPath(String suffix) {
        List<String> parts = new ArrayList<>();
        if (suffix == null || suffix.isBlank()) {
            return parts;
        }
        for (String part : suffix.split("/")) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private String decode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private long parseKeyId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private boolean isValidDomain(String domain) {
        return domain != null && DOMAIN_PATTERN.matcher(domain).matches();
    }

    private boolean isValidSelector(String selector) {
        return selector != null && SELECTOR_PATTERN.matcher(selector).matches();
    }

    private String normalizeAlgorithm(String value) {
        if (value == null || value.isBlank()) {
            return "RSA_2048";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "RSA", "RSA2048", "RSA_2048" -> "RSA_2048";
            case "ED25519", "ED_25519" -> "ED25519";
            default -> null;
        };
    }

    private DkimStrategy parseStrategy(String value) {
        if (value == null || value.isBlank()) {
            return DkimStrategy.MANUAL;
        }
        try {
            return DkimStrategy.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DkimStrategy.MANUAL;
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String toIso(OffsetDateTime dateTime) {
        return dateTime == null ? null : dateTime.toString();
    }

    private DkimKeyRepository repository() {
        return repositorySupplier.get();
    }

    private <T> T parseBody(HttpExchange exchange, Class<T> type) throws IOException {
        byte[] bytes = readAll(exchange.getRequestBody());
        if (bytes.length == 0) {
            return null;
        }
        try {
            return gson.fromJson(new String(bytes, StandardCharsets.UTF_8), type);
        } catch (JsonSyntaxException e) {
            throw new IOException("Invalid JSON body", e);
        }
    }

    private byte[] readAll(InputStream inputStream) throws IOException {
        return inputStream.readAllBytes();
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object payload) throws IOException {
        String body = gson.toJson(payload);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
