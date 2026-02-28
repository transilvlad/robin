package com.mimecast.robin.endpoints;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * Directory listing generator for the storage browser.
 * <p>Generates HTML content for directory listings, showing .eml files and all
 * directories, including Maildir internals (<code>cur</code>, <code>new</code>, <code>tmp</code>).
 */
public class StorageDirectoryListing {
    private static final Logger log = LogManager.getLogger(StorageDirectoryListing.class);

    private final String baseUrl;

    /**
     * Creates a new directory listing generator.
     *
     * @param baseUrl  The base URL path (e.g., "/store").
     */
    public StorageDirectoryListing(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Generates HTML items for the given directory path.
     *
     * @param targetPath    The directory to list.
     * @param relativePath  The relative path from storage root (for URL generation).
     * @return HTML string containing the list items.
     * @throws IOException If directory cannot be read.
     */
    public String generateItems(Path targetPath, String relativePath) throws IOException {
        List<Path> children;
        try (Stream<Path> stream = Files.list(targetPath)) {
            children = stream.sorted().toList();
        }

        StringBuilder items = new StringBuilder(Math.max(4096, children.size() * 128));

        // Parent link if not root
        if (!relativePath.isEmpty()) {
            addParentLink(items, relativePath);
        }

        // Add child directories and files
        for (Path child : children) {
            String name = child.getFileName().toString();
            if (Files.isDirectory(child)) {
                addDirectoryItem(items, name, relativePath);
            } else if (isEmlFile(child)) {
                addFileItem(items, child, name, relativePath);
            }
        }

        return items.toString();
    }

    /**
     * Adds a parent directory navigation link.
     */
    private void addParentLink(StringBuilder items, String relativePath) {
        Path relPath = Paths.get(relativePath);
        Path parent = relPath.getParent();
        String parentHref = baseUrl + "/" + (parent == null ? "" : parent.toString().replace('\\', '/'));
        items.append("<li><span class='dir-icon'>📁</span><a href=\"")
                .append(parentHref).append("\">.. (parent)</a></li>");
    }

    /**
     * Adds a directory item to the listing.
     */
    private void addDirectoryItem(StringBuilder items, String name, String relativePath) {
        String href = baseUrl + "/" + (relativePath.isEmpty() ? name : relativePath + "/" + name) + "/";
        items.append("<li><span class='dir-icon'>📁</span><a href=\"")
                .append(href).append("\">")
                .append(escapeHtml(name)).append("</a></li>");
    }

    /**
     * Adds a file item to the listing.
     */
    private void addFileItem(StringBuilder items, Path filePath, String name, String relativePath) {
        String href = baseUrl + "/" + (relativePath.isEmpty() ? name : relativePath + "/" + name);
        long size = getFileSize(filePath);
        items.append("<li><span class='file-icon'>📧</span><a href=\"")
                .append(href).append("\">")
                .append(escapeHtml(name))
                .append("</a> <span class='description'>&nbsp;(")
                .append(size).append(" bytes)</span></li>");
    }

    /**
     * Gets the file size safely.
     */
    private long getFileSize(Path filePath) {
        try {
            return Files.size(filePath);
        } catch (IOException e) {
            log.debug("Could not get size for file: {}", filePath, e);
            return 0L;
        }
    }

    /**
     * Checks if a file is an .eml file.
     */
    private boolean isEmlFile(Path path) {
        return Files.isRegularFile(path) &&
               path.getFileName().toString().toLowerCase().endsWith(".eml");
    }

    /**
     * Escape minimal HTML characters.
     */
    private String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                case '&' -> out.append("&amp;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
