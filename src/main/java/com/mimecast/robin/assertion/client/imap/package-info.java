/**
 * An IMAP client for retrieving and verifying emails as part of assertions.
 *
 * <p>This package contains classes for IMAP-based email verification assertions.
 *
 * <p>The {@link com.mimecast.robin.assertion.client.imap.ImapExternalClient} connects to an
 * IMAP server, fetches messages by Message-ID, and verifies headers and body parts against
 * configured regex patterns.
 *
 * <p>Features:
 * <ul>
 *   <li>Header pattern matching with regex support</li>
 *   <li>MIME part body assertions with optional header filtering</li>
 *   <li>Automatic cleanup: delete verified message or purge entire folder</li>
 *   <li>Retry logic with configurable wait, delay, and retry count</li>
 * </ul>
 *
 * @see com.mimecast.robin.assertion.client.imap.ImapExternalClient
 * @see com.mimecast.robin.imap.ImapClient
 */
package com.mimecast.robin.assertion.client.imap;
