/**
 * Everything required for building and parsing MIME messages.
 *
 * <p>This package provides comprehensive MIME email processing capabilities for Java applications.
 * <br>These reusable components handle parsing and construction of RFC 2822 compliant email messages
 * <br>with full support for multipart structures, various content encodings, and attachment handling.
 *
 * <p>The {@link com.mimecast.robin.mime.EmailParser} extracts headers, body content, and attachments from existing email files.
 * <br>It implements {@code AutoCloseable} and automatically cleans up temporary files created for MIME parts.
 * <br>Features include:
 * <ul>
 *     <li>Multi-line header folding</li>
 *     <li>Multipart messages (mixed, related, alternative)</li>
 *     <li>Content encodings (Base64, Quoted-Printable)</li>
 *     <li>Content hashing (SHA-1, SHA-256, MD5)</li>
 *     <li>Automatic cleanup of temporary files</li>
 * </ul>
 *
 * <p>The {@link com.mimecast.robin.mime.EmailBuilder} constructs complete RFC 2822 emails programmatically.
 * <br>It can be used to create simple text emails or complex multipart emails with attachments.
 * <br>Features include:
 * <ul>
 *     <li>Fluent API for method chaining</li>
 *     <li>Automatic header generation (Date, Message-ID, etc.)</li>
 *     <li>Part categorization (mixed, related, alternative)</li>
 *     <li>RFC 2047 header encoding for non-ASCII content</li>
 *     <li>Magic token replacement for variable substitution</li>
 * </ul>
 *
 * <p>This library can be integrated into any Java application requiring email processing:
 * <br>MTA implementations, email clients, testing frameworks, forensic tools, or notification systems.
 *
 * @see com.mimecast.robin.mime.EmailParser
 * @see com.mimecast.robin.mime.EmailBuilder
 * @see com.mimecast.robin.mime.headers.MimeHeaders
 * @see com.mimecast.robin.mime.parts.MimePart
 */
package com.mimecast.robin.mime;

