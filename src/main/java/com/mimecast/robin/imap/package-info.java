/**
 * A lightweight IMAP client for fetching and managing emails.
 *
 * <p>This package contains a simple IMAP client for fetching emails, searching by Message-ID,
 * deleting messages, and extracting MIME parts.
 *
 * <p>The {@link com.mimecast.robin.imap.ImapClient} is a lightweight convenience wrapper
 * <br>used by tests and utilities to connect to an IMAP server,
 * <br>open a folder (defaults to INBOX) and fetch messages.
 * <br>It is intentionally small and focused for testing and automation use-cases.
 *
 * <p>Key features:
 * <ul>
 *   <li>Server-side IMAP SEARCH by Message-ID for efficient lookups</li>
 *   <li>Single message deletion after verification</li>
 *   <li>Folder purge for test cleanup</li>
 *   <li>MIME part extraction with headers and decoded body content</li>
 * </ul>
 *
 * @see com.mimecast.robin.imap.ImapClient
 */
package com.mimecast.robin.imap;
