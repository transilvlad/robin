package com.mimecast.robin.storage.rocksdb;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * RocksDB-backed mailbox store for Robin webmail-style access patterns.
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity"})
public class RocksDbMailboxStore implements MailboxStore {
    private static final Logger log = LogManager.getLogger(RocksDbMailboxStore.class);
    private static final Gson GSON = new Gson();
    private static final String SEP = "\u0000";
    private static final String KEY_MESSAGE = "msg";
    private static final String KEY_BLOB = "blob";
    private static final String KEY_FOLDER = "folder";
    private static final String KEY_USER_INDEX = "idxu";
    private static final String KEY_FOLDER_INDEX = "idxf";
    private static final String KEY_STATE_INDEX = "idxs";

    static {
        RocksDB.loadLibrary();
    }

    private final Options options;
    private final RocksDB db;
    private final String dbPath;
    private final String inboxFolder;
    private final String sentFolder;

    public RocksDbMailboxStore(String dbPath, String inboxFolder, String sentFolder) throws IOException {
        try {
            this.dbPath = Path.of(dbPath).toAbsolutePath().normalize().toString();
            Files.createDirectories(Path.of(this.dbPath));
            this.inboxFolder = normalizeRootFolderName(inboxFolder, "Inbox");
            this.sentFolder = normalizeRootFolderName(sentFolder, "Sent");
            this.options = new Options()
                    .setCreateIfMissing(true)
                    .setCompressionType(org.rocksdb.CompressionType.LZ4_COMPRESSION)
                    .setParanoidChecks(true);
            this.db = RocksDB.open(this.options, this.dbPath);
        } catch (RocksDBException e) {
            throw new IOException("Unable to open RocksDB mailbox store: " + dbPath, e);
        }
    }

    public String getDbPath() {
        return dbPath;
    }

    public String getInboxFolder() {
        return inboxFolder;
    }

    public String getSentFolder() {
        return sentFolder;
    }

    public synchronized MailboxView getMailbox(String domain, String user, String state) throws IOException {
        MailboxOwner owner = owner(domain, user);
        ensureDefaultFolders(owner);
        List<MessageRecord> messages = listMessagesInternal(owner, null, normalizeState(state));
        List<FolderSummary> folders = summarizeFolders(owner, messages);
        return new MailboxView(owner.domain, owner.user, null, normalizeState(state), folders, toMessageSummaries(messages));
    }

    public synchronized FolderView getFolder(String domain, String user, String folder, String state) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String normalizedFolder = normalizeFolderPath(folder, null);
        ensureFolder(owner, normalizedFolder, isSystemFolder(normalizedFolder));
        List<MessageRecord> messages = listMessagesInternal(owner, normalizedFolder, normalizeState(state));
        FolderProperties properties = buildFolderProperties(owner, normalizedFolder, messages);
        return new FolderView(owner.domain, owner.user, normalizedFolder, normalizeState(state), properties, toMessageSummaries(messages));
    }

    public synchronized FolderProperties getFolderProperties(String domain, String user, String folder) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String normalizedFolder = normalizeFolderPath(folder, null);
        ensureFolder(owner, normalizedFolder, isSystemFolder(normalizedFolder));
        return buildFolderProperties(owner, normalizedFolder, listMessagesInternal(owner, normalizedFolder, null));
    }

    public synchronized Optional<MessageContent> getMessage(String domain, String user, String messageId) throws IOException {
        MailboxOwner owner = owner(domain, user);
        MessageRecord record = loadMessage(messageId);
        if (record == null || !record.belongsTo(owner.domain, owner.user)) {
            return Optional.empty();
        }
        byte[] bytes = get(blobKey(record.id));
        String content = bytes == null ? "" : new String(bytes, StandardCharsets.ISO_8859_1);
        return Optional.of(new MessageContent(toMessageSummary(record), content));
    }

    public synchronized void createFolder(String domain, String user, String parent, String name) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String normalizedName = normalizeFolderName(name);
        String normalizedParent = normalizeFolderPath(parent, "");
        String folder = normalizedParent.isBlank() ? normalizedName : normalizedParent + "/" + normalizedName;
        ensureFolder(owner, folder, false);
    }

    public synchronized void renameFolder(String domain, String user, String folder, String newName) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String source = normalizeFolderPath(folder, null);
        if (isSystemFolder(source)) {
            throw new IOException("Cannot modify system folder.");
        }
        String normalizedName = normalizeFolderName(newName);
        int slash = source.lastIndexOf('/');
        String target = slash >= 0 ? source.substring(0, slash + 1) + normalizedName : normalizedName;
        moveFolderInternal(owner, source, target, false);
    }

    public synchronized void moveFolder(String domain, String user, String folder, String destinationParent) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String source = normalizeFolderPath(folder, null);
        if (isSystemFolder(source)) {
            throw new IOException("Cannot modify system folder.");
        }
        String parent = normalizeFolderPath(destinationParent, "");
        String leaf = leafName(source);
        String target = parent.isBlank() ? leaf : parent + "/" + leaf;
        moveFolderInternal(owner, source, target, false);
    }

    public synchronized void copyFolder(String domain, String user, String folder, String destinationParent, String newName) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String source = normalizeFolderPath(folder, null);
        String parent = normalizeFolderPath(destinationParent, "");
        String leaf = newName == null || newName.isBlank() ? leafName(source) : normalizeFolderName(newName);
        String target = parent.isBlank() ? leaf : parent + "/" + leaf;
        moveFolderInternal(owner, source, target, true);
    }

    public synchronized void deleteFolder(String domain, String user, String folder) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String normalizedFolder = normalizeFolderPath(folder, null);
        if (isSystemFolder(normalizedFolder)) {
            throw new IOException("Cannot modify system folder.");
        }
        if (!folderExists(owner, normalizedFolder)) {
            throw new IOException("Folder not found.");
        }
        if (!listFolderTreeMessages(owner, normalizedFolder).isEmpty()) {
            throw new IOException("Folder is not empty.");
        }
        for (FolderRecord record : listFolderTree(owner, normalizedFolder)) {
            delete(folderKey(owner, record.path));
        }
    }

    public synchronized MessageSummary storeInbound(String recipient, byte[] content, String sourceFile, Map<String, String> headers) throws IOException {
        MailboxOwner owner = ownerFromAddress(recipient);
        ensureDefaultFolders(owner);
        return toMessageSummary(putMessage(owner, inboxFolder, false, content, sourceFile, headers));
    }

    public synchronized MessageSummary storeOutbound(String sender, byte[] content, String sourceFile, Map<String, String> headers) throws IOException {
        MailboxOwner owner = ownerFromAddress(sender);
        ensureDefaultFolders(owner);
        return toMessageSummary(putMessage(owner, sentFolder, true, content, sourceFile, headers));
    }

    public synchronized int moveMessages(String domain, String user, String fromFolder, String toFolder, List<String> messageIds) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String normalizedFrom = normalizeFolderPath(fromFolder, inboxFolder);
        String normalizedTo = normalizeFolderPath(toFolder, inboxFolder);
        ensureFolder(owner, normalizedTo, isSystemFolder(normalizedTo));
        int moved = 0;
        for (String messageId : normalizeMessageIds(messageIds)) {
            MessageRecord record = loadMessage(messageId);
            if (record == null || !record.belongsTo(owner.domain, owner.user)) {
                continue;
            }
            if (!record.folder.equals(normalizedFrom)) {
                continue;
            }
            updateMessage(record, normalizedTo, record.read);
            moved++;
        }
        return moved;
    }

    public synchronized int updateReadStatus(String domain, String user, String folder, String action, List<String> messageIds) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String normalizedFolder = normalizeFolderPath(folder, inboxFolder);
        boolean read;
        if ("read".equalsIgnoreCase(action)) {
            read = true;
        } else if ("unread".equalsIgnoreCase(action)) {
            read = false;
        } else {
            throw new IOException("Invalid action");
        }
        int moved = 0;
        for (String messageId : normalizeMessageIds(messageIds)) {
            MessageRecord record = loadMessage(messageId);
            if (record == null || !record.belongsTo(owner.domain, owner.user) || !record.folder.equals(normalizedFolder)) {
                continue;
            }
            updateMessage(record, record.folder, read);
            moved++;
        }
        return moved;
    }

    public synchronized int markAllRead(String domain, String user, String folder) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String normalizedFolder = normalizeFolderPath(folder, inboxFolder);
        int moved = 0;
        for (MessageRecord record : listMessagesInternal(owner, normalizedFolder, "unread")) {
            updateMessage(record, record.folder, true);
            moved++;
        }
        return moved;
    }

    public synchronized int deleteAllMessages(String domain, String user, String folder) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String normalizedFolder = normalizeFolderPath(folder, inboxFolder);
        int deleted = 0;
        for (MessageRecord record : listMessagesInternal(owner, normalizedFolder, null)) {
            deleteMessage(record);
            deleted++;
        }
        return deleted;
    }

    public synchronized int cleanupMessages(String domain, String user, String folder, int months) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String normalizedFolder = normalizeFolderPath(folder, inboxFolder);
        Instant cutoff = ZonedDateTime.now(ZoneOffset.UTC)
                .minusMonths(Math.max(0, months))
                .toInstant();
        int affected = 0;
        for (MessageRecord record : listFolderTreeMessages(owner, normalizedFolder)) {
            if (Instant.ofEpochMilli(record.receivedAt).isBefore(cutoff)) {
                deleteMessage(record);
                affected++;
            }
        }
        return affected;
    }

    public synchronized void clearAll() throws IOException {
        try (RocksIterator iterator = db.newIterator()) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                db.delete(iterator.key());
                iterator.next();
            }
        } catch (RocksDBException e) {
            throw new IOException("Unable to clear RocksDB mailbox store", e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            db.close();
            options.close();
        } catch (Exception e) {
            throw new IOException("Unable to close RocksDB mailbox store", e);
        }
    }

    private void moveFolderInternal(MailboxOwner owner, String source, String target, boolean copy) throws IOException {
        if (source.equals(target)) {
            return;
        }
        if (!folderExists(owner, source)) {
            throw new IOException("Folder not found.");
        }
        if (target.startsWith(source + "/")) {
            throw new IOException("Cannot move folder into itself.");
        }
        ensureFolder(owner, parentPath(target), false);
        List<FolderRecord> folders = listFolderTree(owner, source);
        List<MessageRecord> messages = listFolderTreeMessages(owner, source);
        if (!copy) {
            for (FolderRecord folderRecord : folders) {
                delete(folderKey(owner, folderRecord.path));
            }
        }
        for (FolderRecord folderRecord : folders) {
            String newPath = replaceFolderPrefix(folderRecord.path, source, target);
            ensureFolder(owner, newPath, folderRecord.system && isSystemFolder(newPath));
        }
        for (MessageRecord record : messages) {
            String newFolder = replaceFolderPrefix(record.folder, source, target);
            if (copy) {
                putMessage(owner, newFolder, record.read, getBlob(record.id), record.sourceFile, record.headers);
            } else {
                updateMessage(record, newFolder, record.read);
            }
        }
    }

    private MessageRecord putMessage(MailboxOwner owner, String folder, boolean read, byte[] content, String sourceFile, Map<String, String> headers) throws IOException {
        ensureFolder(owner, folder, isSystemFolder(folder));
        long now = System.currentTimeMillis();
        String id = "msg-" + now + "-" + UUID.randomUUID() + ".eml";
        MessageRecord record = new MessageRecord();
        record.id = id;
        record.domain = owner.domain;
        record.user = owner.user;
        record.folder = folder;
        record.read = read;
        record.receivedAt = now;
        record.updatedAt = now;
        record.size = content.length;
        record.sourceFile = sourceFile == null ? "" : sourceFile;
        record.subject = headerValue(headers, "Subject");
        record.from = headerValue(headers, "From");
        record.to = headerValue(headers, "To");
        record.headers = sanitizeHeaders(headers);
        writeMessage(record, content);
        return record;
    }

    private void writeMessage(MessageRecord record, byte[] content) throws IOException {
        try {
            db.put(bytes(messageKey(record.id)), bytes(GSON.toJson(record)));
            db.put(bytes(blobKey(record.id)), content);
            putIndex(record);
        } catch (RocksDBException e) {
            throw new IOException("Unable to write message " + record.id, e);
        }
    }

    private void updateMessage(MessageRecord record, String folder, boolean read) throws IOException {
        removeIndex(record);
        record.folder = folder;
        record.read = read;
        record.updatedAt = System.currentTimeMillis();
        writeMessage(record, getBlob(record.id));
    }

    private void deleteMessage(MessageRecord record) throws IOException {
        try {
            removeIndex(record);
            db.delete(bytes(messageKey(record.id)));
            db.delete(bytes(blobKey(record.id)));
        } catch (RocksDBException e) {
            throw new IOException("Unable to delete message " + record.id, e);
        }
    }

    private void putIndex(MessageRecord record) throws IOException {
        try {
            db.put(bytes(userIndexKey(record)), new byte[0]);
            db.put(bytes(folderIndexKey(record)), new byte[0]);
            db.put(bytes(stateIndexKey(record)), new byte[0]);
        } catch (RocksDBException e) {
            throw new IOException("Unable to index message " + record.id, e);
        }
    }

    private void removeIndex(MessageRecord record) throws IOException {
        try {
            db.delete(bytes(userIndexKey(record)));
            db.delete(bytes(folderIndexKey(record)));
            db.delete(bytes(stateIndexKey(record)));
        } catch (RocksDBException e) {
            throw new IOException("Unable to remove index for message " + record.id, e);
        }
    }

    private FolderProperties buildFolderProperties(MailboxOwner owner, String folder, List<MessageRecord> messages) {
        FolderProperties properties = new FolderProperties();
        properties.r = 1;
        properties.folder = folder;
        properties.total = messages.size();
        properties.unread = (int) messages.stream().filter(record -> !record.read).count();
        properties.read = properties.total - properties.unread;
        properties.size = messages.stream().mapToLong(record -> record.size).sum();
        properties.domain = owner.domain;
        properties.user = owner.user;
        return properties;
    }

    private List<FolderSummary> summarizeFolders(MailboxOwner owner, List<MessageRecord> rootMessages) throws IOException {
        Map<String, FolderSummary> summaries = new LinkedHashMap<>();
        for (FolderRecord record : listFolders(owner)) {
            FolderSummary summary = new FolderSummary();
            summary.path = record.path;
            summary.system = record.system;
            summary.total = 0;
            summary.unread = 0;
            summaries.put(record.path, summary);
        }
        for (MessageRecord message : rootMessages) {
            FolderSummary summary = summaries.computeIfAbsent(message.folder, ignored -> {
                FolderSummary created = new FolderSummary();
                created.path = message.folder;
                return created;
            });
            summary.total++;
            if (!message.read) {
                summary.unread++;
            }
        }
        return summaries.values().stream()
                .sorted(Comparator.comparing(summary -> summary.path.toLowerCase(Locale.ROOT)))
                .toList();
    }

    private List<MessageSummary> toMessageSummaries(List<MessageRecord> messages) {
        return messages.stream().map(this::toMessageSummary).toList();
    }

    private MessageSummary toMessageSummary(MessageRecord record) {
        MessageSummary summary = new MessageSummary();
        summary.id = record.id;
        summary.folder = record.folder;
        summary.read = record.read;
        summary.receivedAt = record.receivedAt;
        summary.updatedAt = record.updatedAt;
        summary.size = record.size;
        summary.subject = record.subject;
        summary.from = record.from;
        summary.to = record.to;
        summary.sourceFile = record.sourceFile;
        return summary;
    }

    private List<MessageRecord> listMessagesInternal(MailboxOwner owner, String folder, String state) throws IOException {
        String prefix;
        if (folder != null && state != null) {
            prefix = join(KEY_STATE_INDEX, owner.key(), folder, state);
        } else if (folder != null) {
            prefix = join(KEY_FOLDER_INDEX, owner.key(), folder);
        } else {
            prefix = join(KEY_USER_INDEX, owner.key());
        }

        List<MessageRecord> messages = new ArrayList<>();
        for (String key : scan(prefix)) {
            String id = extractTrailingSegment(key);
            MessageRecord record = loadMessage(id);
            if (record == null || !record.belongsTo(owner.domain, owner.user)) {
                continue;
            }
            if (folder != null && !record.folder.equals(folder)) {
                continue;
            }
            if (state != null && !state.equals(record.state())) {
                continue;
            }
            messages.add(record);
        }
        return messages;
    }

    private List<MessageRecord> listFolderTreeMessages(MailboxOwner owner, String folder) throws IOException {
        List<MessageRecord> out = new ArrayList<>();
        for (MessageRecord record : listMessagesInternal(owner, null, null)) {
            if (record.folder.equals(folder) || record.folder.startsWith(folder + "/")) {
                out.add(record);
            }
        }
        return out;
    }

    private List<FolderRecord> listFolders(MailboxOwner owner) throws IOException {
        List<FolderRecord> folders = new ArrayList<>();
        for (String key : scan(join(KEY_FOLDER, owner.key()))) {
            byte[] raw = get(key);
            if (raw == null) {
                continue;
            }
            folders.add(GSON.fromJson(new String(raw, StandardCharsets.UTF_8), FolderRecord.class));
        }
        return folders;
    }

    private List<FolderRecord> listFolderTree(MailboxOwner owner, String folder) throws IOException {
        List<FolderRecord> folders = new ArrayList<>();
        for (FolderRecord record : listFolders(owner)) {
            if (record.path.equals(folder) || record.path.startsWith(folder + "/")) {
                folders.add(record);
            }
        }
        folders.sort(Comparator.comparingInt(record -> record.path.length()));
        return folders;
    }

    private boolean folderExists(MailboxOwner owner, String folder) throws IOException {
        return get(folderKey(owner, folder)) != null;
    }

    private void ensureDefaultFolders(MailboxOwner owner) throws IOException {
        ensureFolder(owner, inboxFolder, true);
        ensureFolder(owner, sentFolder, true);
    }

    private void ensureFolder(MailboxOwner owner, String folder, boolean system) throws IOException {
        if (folder == null || folder.isBlank()) {
            return;
        }
        String current = "";
        for (String segment : folder.split("/")) {
            current = appendPath(current, segment);
            ensureSingleFolder(owner, current, system && current.equals(folder));
        }
    }

    private void ensureSingleFolder(MailboxOwner owner, String folder, boolean system) throws IOException {
        if (folder == null || folder.isBlank()) {
            return;
        }
        FolderRecord existing = loadFolder(owner, folder);
        if (existing != null) {
            if (system && !existing.system) {
                existing.system = true;
                writeFolder(owner, existing);
            }
            return;
        }
        FolderRecord record = new FolderRecord();
        record.domain = owner.domain;
        record.user = owner.user;
        record.path = folder;
        record.system = system;
        record.createdAt = System.currentTimeMillis();
        record.updatedAt = record.createdAt;
        writeFolder(owner, record);
    }

    private FolderRecord loadFolder(MailboxOwner owner, String folder) throws IOException {
        byte[] raw = get(folderKey(owner, folder));
        if (raw == null) {
            return null;
        }
        return GSON.fromJson(new String(raw, StandardCharsets.UTF_8), FolderRecord.class);
    }

    private void writeFolder(MailboxOwner owner, FolderRecord record) throws IOException {
        try {
            db.put(bytes(folderKey(owner, record.path)), bytes(GSON.toJson(record)));
        } catch (RocksDBException e) {
            throw new IOException("Unable to write folder " + record.path, e);
        }
    }

    private MessageRecord loadMessage(String id) throws IOException {
        byte[] raw = get(messageKey(id));
        if (raw == null) {
            return null;
        }
        return GSON.fromJson(new String(raw, StandardCharsets.UTF_8), MessageRecord.class);
    }

    private byte[] getBlob(String id) throws IOException {
        byte[] blob = get(blobKey(id));
        return blob == null ? new byte[0] : blob;
    }

    private byte[] get(String key) throws IOException {
        return get(bytes(key));
    }

    private byte[] get(byte[] key) throws IOException {
        try {
            return db.get(key);
        } catch (RocksDBException e) {
            throw new IOException("Unable to read RocksDB key", e);
        }
    }

    private void delete(String key) throws IOException {
        try {
            db.delete(bytes(key));
        } catch (RocksDBException e) {
            throw new IOException("Unable to delete RocksDB key", e);
        }
    }

    private List<String> scan(String prefix) {
        byte[] prefixBytes = bytes(prefix);
        List<String> keys = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator()) {
            iterator.seek(prefixBytes);
            while (iterator.isValid()) {
                if (!startsWith(iterator.key(), prefixBytes)) {
                    break;
                }
                keys.add(new String(iterator.key(), StandardCharsets.UTF_8));
                iterator.next();
            }
        }
        return keys;
    }

    private boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private List<String> normalizeMessageIds(List<String> ids) {
        List<String> normalized = new ArrayList<>();
        if (ids == null) {
            return normalized;
        }
        for (String raw : ids) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String id = raw.replace('\\', '/');
            if (id.contains("..")) {
                continue;
            }
            int slash = id.lastIndexOf('/');
            normalized.add(slash >= 0 ? id.substring(slash + 1) : id);
        }
        return normalized;
    }

    private MailboxOwner owner(String domain, String user) throws IOException {
        String normalizedDomain = normalizeMailboxPart(domain, "domain");
        String normalizedUser = normalizeMailboxPart(user, "user");
        return new MailboxOwner(normalizedDomain, normalizedUser);
    }

    private MailboxOwner ownerFromAddress(String address) throws IOException {
        if (address == null || !address.contains("@")) {
            throw new IOException("Invalid email address.");
        }
        String[] splits = address.split("@", 2);
        return owner(splits[1], splits[0]);
    }

    private String normalizeMailboxPart(String value, String label) throws IOException {
        String clean = value == null ? "" : value.trim().replace('\\', '/');
        if (clean.isBlank() || clean.contains("/") || clean.contains("..")) {
            throw new IOException("Invalid " + label + ".");
        }
        return clean.toLowerCase(Locale.ROOT);
    }

    private String normalizeFolderPath(String raw, String defaultFolder) throws IOException {
        String clean = raw == null ? "" : raw.trim().replace('\\', '/');
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("/") && !clean.isEmpty()) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.isBlank()) {
            if (defaultFolder == null) {
                throw new IOException("Folder is required.");
            }
            return defaultFolder;
        }
        if (clean.contains("..")) {
            throw new IOException("Forbidden");
        }
        List<String> parts = new ArrayList<>();
        for (String part : clean.split("/")) {
            String trimmed = part == null ? "" : part.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            parts.add(trimmed);
        }
        if (parts.isEmpty()) {
            if (defaultFolder == null) {
                throw new IOException("Folder is required.");
            }
            return defaultFolder;
        }
        String first = parts.getFirst();
        if (first.equalsIgnoreCase("inbox") || first.equalsIgnoreCase(inboxFolder)) {
            parts.set(0, inboxFolder);
        } else if (first.equalsIgnoreCase("sent") || first.equalsIgnoreCase(sentFolder)) {
            parts.set(0, sentFolder);
        }
        return String.join("/", parts);
    }

    private String normalizeFolderName(String name) throws IOException {
        String clean = name == null ? "" : name.trim().replace('\\', '/');
        if (clean.isBlank() || clean.contains("/") || ".".equals(clean) || clean.contains("..")) {
            throw new IOException("Invalid folder name.");
        }
        return clean;
    }

    private String normalizeState(String state) throws IOException {
        if (state == null || state.isBlank()) {
            return null;
        }
        if ("read".equalsIgnoreCase(state)) {
            return "read";
        }
        if ("unread".equalsIgnoreCase(state)) {
            return "unread";
        }
        throw new IOException("Invalid state.");
    }

    private String normalizeRootFolderName(String raw, String fallback) {
        String clean = raw == null ? "" : raw.trim();
        return clean.isBlank() ? fallback : clean;
    }

    private boolean isSystemFolder(String folder) {
        return inboxFolder.equals(folder) || sentFolder.equals(folder);
    }

    private String headerValue(Map<String, String> headers, String key) {
        if (headers == null) {
            return "";
        }
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
    }

    private Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers == null) {
            return out;
        }
        headers.forEach((key, value) -> {
            if (key != null && value != null) {
                out.put(key, value);
            }
        });
        return out;
    }

    private String join(String... parts) {
        return String.join(SEP, parts) + SEP;
    }

    private String messageKey(String id) {
        return KEY_MESSAGE + SEP + id;
    }

    private String blobKey(String id) {
        return KEY_BLOB + SEP + id;
    }

    private String folderKey(MailboxOwner owner, String folder) {
        return join(KEY_FOLDER, owner.key(), folder);
    }

    private String userIndexKey(MessageRecord record) {
        return join(KEY_USER_INDEX, ownerKey(record.domain, record.user), reverseSortKey(record.receivedAt), record.id);
    }

    private String folderIndexKey(MessageRecord record) {
        return join(KEY_FOLDER_INDEX, ownerKey(record.domain, record.user), record.folder, reverseSortKey(record.receivedAt), record.id);
    }

    private String stateIndexKey(MessageRecord record) {
        return join(KEY_STATE_INDEX, ownerKey(record.domain, record.user), record.folder, record.state(), reverseSortKey(record.receivedAt), record.id);
    }

    private String reverseSortKey(long value) {
        return String.format("%019d", Long.MAX_VALUE - value);
    }

    private String ownerKey(String domain, String user) {
        return domain + "/" + user;
    }

    private String replaceFolderPrefix(String value, String source, String target) {
        if (value.equals(source)) {
            return target;
        }
        return target + value.substring(source.length());
    }

    private String parentPath(String folder) {
        if (folder == null || folder.isBlank()) {
            return "";
        }
        int slash = folder.lastIndexOf('/');
        return slash >= 0 ? folder.substring(0, slash) : "";
    }

    private String leafName(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash >= 0 ? folder.substring(slash + 1) : folder;
    }

    private String appendPath(String base, String name) {
        return base == null || base.isBlank() ? name : base + "/" + name;
    }

    private String extractTrailingSegment(String key) {
        String trimmed = key.endsWith(SEP) ? key.substring(0, key.length() - SEP.length()) : key;
        int index = trimmed.lastIndexOf(SEP);
        return index >= 0 ? trimmed.substring(index + SEP.length()) : trimmed;
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class MailboxOwner {
        private final String domain;
        private final String user;

        private MailboxOwner(String domain, String user) {
            this.domain = domain;
            this.user = user;
        }

        private String key() {
            return domain + "/" + user;
        }
    }

    private static final class FolderRecord {
        private String domain;
        private String user;
        private String path;
        private boolean system;
        private long createdAt;
        private long updatedAt;
    }


    private static final class MessageRecord {
        private String id;
        private String domain;
        private String user;
        private String folder;
        private boolean read;
        private long receivedAt;
        private long updatedAt;
        private long size;
        private String subject;
        private String from;
        private String to;
        private String sourceFile;
        private Map<String, String> headers;

        private String state() {
            return read ? "read" : "unread";
        }

        private boolean belongsTo(String domain, String user) {
            return Objects.equals(this.domain, domain) && Objects.equals(this.user, user);
        }
    }
}
