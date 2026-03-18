package com.mimecast.robin.storage.rocksdb;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Interface for mailbox storage operations.
 */
public interface MailboxStore extends Closeable {

    String getDbPath();

    String getInboxFolder();

    String getSentFolder();

    MailboxView getMailbox(String domain, String user, String state) throws IOException;

    FolderView getFolder(String domain, String user, String folder, String state) throws IOException;

    FolderProperties getFolderProperties(String domain, String user, String folder) throws IOException;

    Optional<MessageContent> getMessage(String domain, String user, String messageId) throws IOException;

    void createFolder(String domain, String user, String parent, String name) throws IOException;

    void renameFolder(String domain, String user, String folder, String newName) throws IOException;

    void moveFolder(String domain, String user, String folder, String destinationParent) throws IOException;

    void copyFolder(String domain, String user, String folder, String destinationParent, String newName) throws IOException;

    void deleteFolder(String domain, String user, String folder) throws IOException;

    MessageSummary storeInbound(String recipient, byte[] content, String sourceFile, Map<String, String> headers) throws IOException;

    MessageSummary storeOutbound(String sender, byte[] content, String sourceFile, Map<String, String> headers) throws IOException;

    int moveMessages(String domain, String user, String fromFolder, String toFolder, List<String> messageIds) throws IOException;

    int updateReadStatus(String domain, String user, String folder, String action, List<String> messageIds) throws IOException;

    int markAllRead(String domain, String user, String folder) throws IOException;

    int deleteAllMessages(String domain, String user, String folder) throws IOException;

    int cleanupMessages(String domain, String user, String folder, int months) throws IOException;

    void clearAll() throws IOException;

    class FolderSummary {
        public String path;
        public boolean system;
        public int total;
        public int unread;
    }

    class FolderProperties {
        public int r;
        public String domain;
        public String user;
        public String folder;
        public int total;
        public int unread;
        public int read;
        public long size;
    }

    class MessageSummary {
        public String id;
        public String folder;
        public boolean read;
        public long receivedAt;
        public long updatedAt;
        public long size;
        public String subject;
        public String from;
        public String to;
        public String sourceFile;
    }

    class MessageContent {
        public MessageSummary message;
        public String content;

        public MessageContent(MessageSummary message, String content) {
            this.message = message;
            this.content = content;
        }
    }

    class MailboxView {
        public int r = 1;
        public String domain;
        public String user;
        public String folder;
        public String state;
        public List<FolderSummary> folders;
        public List<MessageSummary> messages;

        public MailboxView(String domain, String user, String folder, String state, List<FolderSummary> folders, List<MessageSummary> messages) {
            this.domain = domain;
            this.user = user;
            this.folder = folder;
            this.state = state;
            this.folders = folders;
            this.messages = messages;
        }
    }

    class FolderView {
        public int r = 1;
        public String domain;
        public String user;
        public String folder;
        public String state;
        public FolderProperties properties;
        public List<MessageSummary> messages;

        public FolderView(String domain, String user, String folder, String state, FolderProperties properties, List<MessageSummary> messages) {
            this.domain = domain;
            this.user = user;
            this.folder = folder;
            this.state = state;
            this.properties = properties;
            this.messages = messages;
        }
    }
}

