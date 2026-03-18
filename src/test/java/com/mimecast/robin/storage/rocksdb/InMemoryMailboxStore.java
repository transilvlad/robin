package com.mimecast.robin.storage.rocksdb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of MailboxStore for unit testing.
 * Does not require native libraries.
 */
public class InMemoryMailboxStore implements MailboxStore {
    private final Map<String, FolderRecord> folders = new ConcurrentHashMap<>();
    private final Map<String, MessageRecord> messages = new ConcurrentHashMap<>();
    private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();

    private final String inboxFolder;
    private final String sentFolder;

    public InMemoryMailboxStore() {
        this("Inbox", "Sent");
    }

    public InMemoryMailboxStore(String inboxFolder, String sentFolder) {
        this.inboxFolder = normalizeRootFolderName(inboxFolder, "Inbox");
        this.sentFolder = normalizeRootFolderName(sentFolder, "Sent");
    }

    @Override
    public String getDbPath() {
        return "memory";
    }

    @Override
    public String getInboxFolder() {
        return inboxFolder;
    }

    @Override
    public String getSentFolder() {
        return sentFolder;
    }

    @Override
    public synchronized MailboxView getMailbox(String domain, String user, String state) throws IOException {
        MailboxOwner owner = owner(domain, user);
        ensureDefaultFolders(owner);
        List<MessageRecord> msgs = listMessagesInternal(owner, null, normalizeState(state));
        List<FolderSummary> folderList = summarizeFolders(owner, msgs);
        return new MailboxView(owner.domain, owner.user, null, normalizeState(state), folderList, toMessageSummaries(msgs));
    }

    @Override
    public synchronized FolderView getFolder(String domain, String user, String folder, String state) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String normalizedFolder = normalizeFolderPath(folder, null);
        ensureFolder(owner, normalizedFolder, isSystemFolder(normalizedFolder));
        List<MessageRecord> msgs = listMessagesInternal(owner, normalizedFolder, normalizeState(state));
        FolderProperties properties = buildFolderProperties(owner, normalizedFolder, msgs);
        return new FolderView(owner.domain, owner.user, normalizedFolder, normalizeState(state), properties, toMessageSummaries(msgs));
    }

    @Override
    public synchronized FolderProperties getFolderProperties(String domain, String user, String folder) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String normalizedFolder = normalizeFolderPath(folder, null);
        ensureFolder(owner, normalizedFolder, isSystemFolder(normalizedFolder));
        return buildFolderProperties(owner, normalizedFolder, listMessagesInternal(owner, normalizedFolder, null));
    }

    @Override
    public synchronized Optional<MessageContent> getMessage(String domain, String user, String messageId) throws IOException {
        MailboxOwner owner = owner(domain, user);
        MessageRecord record = messages.get(messageId);
        if (record == null || !record.belongsTo(owner.domain, owner.user)) {
            return Optional.empty();
        }
        byte[] bytes = blobs.get(record.id);
        String content = bytes == null ? "" : new String(bytes, StandardCharsets.ISO_8859_1);
        return Optional.of(new MessageContent(toMessageSummary(record), content));
    }

    @Override
    public synchronized void createFolder(String domain, String user, String parent, String name) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String normalizedName = normalizeFolderName(name);
        String normalizedParent = normalizeFolderPath(parent, "");
        String folder = normalizedParent.isBlank() ? normalizedName : normalizedParent + "/" + normalizedName;
        ensureFolder(owner, folder, false);
    }

    @Override
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

    @Override
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

    @Override
    public synchronized void copyFolder(String domain, String user, String folder, String destinationParent, String newName) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String source = normalizeFolderPath(folder, null);
        String parent = normalizeFolderPath(destinationParent, "");
        String leaf = newName == null || newName.isBlank() ? leafName(source) : normalizeFolderName(newName);
        String target = parent.isBlank() ? leaf : parent + "/" + leaf;
        moveFolderInternal(owner, source, target, true);
    }

    @Override
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
            folders.remove(folderKey(owner, record.path));
        }
    }

    @Override
    public synchronized MessageSummary storeInbound(String recipient, byte[] content, String sourceFile, Map<String, String> headers) throws IOException {
        MailboxOwner owner = ownerFromAddress(recipient);
        ensureDefaultFolders(owner);
        return toMessageSummary(putMessage(owner, inboxFolder, false, content, sourceFile, headers));
    }

    @Override
    public synchronized MessageSummary storeOutbound(String sender, byte[] content, String sourceFile, Map<String, String> headers) throws IOException {
        MailboxOwner owner = ownerFromAddress(sender);
        ensureDefaultFolders(owner);
        return toMessageSummary(putMessage(owner, sentFolder, true, content, sourceFile, headers));
    }

    @Override
    public synchronized int moveMessages(String domain, String user, String fromFolder, String toFolder, List<String> messageIds) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String normalizedFrom = normalizeFolderPath(fromFolder, inboxFolder);
        String normalizedTo = normalizeFolderPath(toFolder, inboxFolder);
        ensureFolder(owner, normalizedTo, isSystemFolder(normalizedTo));
        int moved = 0;
        for (String messageId : normalizeMessageIds(messageIds)) {
            MessageRecord record = messages.get(messageId);
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

    @Override
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
        int updated = 0;
        for (String messageId : normalizeMessageIds(messageIds)) {
            MessageRecord record = messages.get(messageId);
            if (record == null || !record.belongsTo(owner.domain, owner.user) || !record.folder.equals(normalizedFolder)) {
                continue;
            }
            updateMessage(record, record.folder, read);
            updated++;
        }
        return updated;
    }

    @Override
    public synchronized int markAllRead(String domain, String user, String folder) throws IOException {
        MailboxOwner owner = owner(domain, user);
        String normalizedFolder = normalizeFolderPath(folder, inboxFolder);
        int updated = 0;
        for (MessageRecord record : listMessagesInternal(owner, normalizedFolder, "unread")) {
            updateMessage(record, record.folder, true);
            updated++;
        }
        return updated;
    }

    @Override
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

    @Override
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

    @Override
    public synchronized void clearAll() {
        folders.clear();
        messages.clear();
        blobs.clear();
    }

    @Override
    public void close() {
        clearAll();
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
        List<FolderRecord> folderList = listFolderTree(owner, source);
        List<MessageRecord> messageList = listFolderTreeMessages(owner, source);
        if (!copy) {
            for (FolderRecord folderRecord : folderList) {
                folders.remove(folderKey(owner, folderRecord.path));
            }
        }
        for (FolderRecord folderRecord : folderList) {
            String newPath = replaceFolderPrefix(folderRecord.path, source, target);
            ensureFolder(owner, newPath, folderRecord.system && isSystemFolder(newPath));
        }
        for (MessageRecord record : messageList) {
            String newFolder = replaceFolderPrefix(record.folder, source, target);
            if (copy) {
                byte[] blob = blobs.get(record.id);
                putMessage(owner, newFolder, record.read, blob, record.sourceFile, record.headers);
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
        messages.put(id, record);
        blobs.put(id, content);
        return record;
    }

    private void updateMessage(MessageRecord record, String folder, boolean read) {
        record.folder = folder;
        record.read = read;
        record.updatedAt = System.currentTimeMillis();
    }

    private void deleteMessage(MessageRecord record) {
        messages.remove(record.id);
        blobs.remove(record.id);
    }

    private void ensureDefaultFolders(MailboxOwner owner) throws IOException {
        ensureFolder(owner, inboxFolder, true);
        ensureFolder(owner, sentFolder, true);
    }

    private void ensureFolder(MailboxOwner owner, String folder, boolean system) throws IOException {
        if (folder == null || folder.isBlank()) {
            return;
        }
        String parent = parentPath(folder);
        if (!parent.isBlank()) {
            ensureFolder(owner, parent, false);
        }
        String key = folderKey(owner, folder);
        if (!folders.containsKey(key)) {
            long now = System.currentTimeMillis();
            FolderRecord record = new FolderRecord();
            record.domain = owner.domain;
            record.user = owner.user;
            record.path = folder;
            record.system = system;
            record.createdAt = now;
            record.updatedAt = now;
            folders.put(key, record);
        }
    }

    private boolean folderExists(MailboxOwner owner, String folder) {
        return folders.containsKey(folderKey(owner, folder));
    }

    private List<FolderRecord> listFolderTree(MailboxOwner owner, String root) {
        String prefix = folderKey(owner, root);
        List<FolderRecord> result = new ArrayList<>();
        for (Map.Entry<String, FolderRecord> entry : folders.entrySet()) {
            if (entry.getKey().equals(prefix) || entry.getKey().startsWith(prefix + "/")) {
                result.add(entry.getValue());
            }
        }
        return result;
    }

    private List<MessageRecord> listFolderTreeMessages(MailboxOwner owner, String root) {
        List<MessageRecord> result = new ArrayList<>();
        for (MessageRecord record : messages.values()) {
            if (record.belongsTo(owner.domain, owner.user)) {
                if (record.folder.equals(root) || record.folder.startsWith(root + "/")) {
                    result.add(record);
                }
            }
        }
        return result;
    }

    private List<MessageRecord> listMessagesInternal(MailboxOwner owner, String folder, String state) {
        List<MessageRecord> result = new ArrayList<>();
        for (MessageRecord record : messages.values()) {
            if (!record.belongsTo(owner.domain, owner.user)) {
                continue;
            }
            if (folder != null && !record.folder.equals(folder)) {
                continue;
            }
            if (state != null && !record.state().equals(state)) {
                continue;
            }
            result.add(record);
        }
        result.sort(Comparator.comparingLong((MessageRecord r) -> r.receivedAt).reversed());
        return result;
    }

    private List<FolderSummary> summarizeFolders(MailboxOwner owner, List<MessageRecord> allMessages) {
        Map<String, FolderSummary> summaries = new LinkedHashMap<>();
        for (FolderRecord folder : folders.values()) {
            if (folder.domain.equals(owner.domain) && folder.user.equals(owner.user)) {
                FolderSummary summary = new FolderSummary();
                summary.path = folder.path;
                summary.system = folder.system;
                summaries.put(folder.path, summary);
            }
        }
        for (MessageRecord record : allMessages) {
            FolderSummary summary = summaries.get(record.folder);
            if (summary != null) {
                summary.total++;
                if (!record.read) {
                    summary.unread++;
                }
            }
        }
        return new ArrayList<>(summaries.values());
    }

    private FolderProperties buildFolderProperties(MailboxOwner owner, String folder, List<MessageRecord> msgs) {
        FolderProperties props = new FolderProperties();
        props.r = 1;
        props.domain = owner.domain;
        props.user = owner.user;
        props.folder = folder;
        for (MessageRecord record : msgs) {
            props.total++;
            props.size += record.size;
            if (record.read) {
                props.read++;
            } else {
                props.unread++;
            }
        }
        return props;
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

    private List<MessageSummary> toMessageSummaries(List<MessageRecord> records) {
        List<MessageSummary> result = new ArrayList<>();
        for (MessageRecord record : records) {
            result.add(toMessageSummary(record));
        }
        return result;
    }

    private MailboxOwner owner(String domain, String user) throws IOException {
        String normalizedDomain = normalizeDomain(domain);
        String normalizedUser = normalizeUser(user);
        if (normalizedDomain.isEmpty() || normalizedUser.isEmpty()) {
            throw new IOException("Invalid mailbox owner.");
        }
        return new MailboxOwner(normalizedDomain, normalizedUser);
    }

    private MailboxOwner ownerFromAddress(String address) throws IOException {
        if (address == null || address.isBlank()) {
            throw new IOException("Invalid address.");
        }
        int at = address.indexOf('@');
        if (at < 0) {
            throw new IOException("Invalid address format.");
        }
        return owner(address.substring(at + 1), address.substring(0, at));
    }

    private String folderKey(MailboxOwner owner, String folder) {
        return owner.key() + "/" + folder;
    }

    private boolean isSystemFolder(String folder) {
        return folder != null && (folder.equalsIgnoreCase(inboxFolder) || folder.equalsIgnoreCase(sentFolder));
    }

    private String normalizeState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String lower = state.toLowerCase(Locale.ROOT);
        if ("read".equals(lower) || "unread".equals(lower)) {
            return lower;
        }
        return null;
    }

    private String normalizeDomain(String domain) {
        return domain == null ? "" : domain.toLowerCase(Locale.ROOT).trim();
    }

    private String normalizeUser(String user) {
        return user == null ? "" : user.toLowerCase(Locale.ROOT).trim();
    }

    private String normalizeFolderPath(String folder, String defaultValue) {
        if (folder == null || folder.isBlank()) {
            return defaultValue == null ? "" : defaultValue;
        }
        String clean = folder.trim();
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("/") && !clean.isEmpty()) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    private String normalizeFolderName(String name) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IOException("Folder name is required.");
        }
        String trimmed = name.trim();
        if (trimmed.contains("/")) {
            throw new IOException("Folder name cannot contain slashes.");
        }
        return trimmed;
    }

    private String normalizeRootFolderName(String name, String defaultName) {
        if (name == null || name.isBlank()) {
            return defaultName;
        }
        String trimmed = name.trim();
        return trimmed.contains("/") ? defaultName : trimmed;
    }

    private List<String> normalizeMessageIds(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String id : ids) {
            String trimmed = id == null ? "" : id.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private String headerValue(Map<String, String> headers, String name) {
        if (headers == null) {
            return "";
        }
        String value = headers.get(name);
        return value == null ? "" : value;
    }

    private Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        if (headers == null) {
            return new LinkedHashMap<>();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
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

