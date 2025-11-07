/**
 * Deals with scanning emails for SPAM, viruses and other potential threats.
 *
 * <p>Robin can be configured to scan emails for SPAM using Rspamd.
 * <br>Rspamd integration is configured in the {@code cfg/rspamd.json5} file.
 *
 * <p>Robin can be configured to scan emails for viruses using ClamAV.
 * <br>ClamAV integration is configured in the {@code cfg/clamav.json5} file.
 *
 * @see com.mimecast.robin.scanners.RspamdClient
 * @see com.mimecast.robin.scanners.ClamAVClient
 */
package com.mimecast.robin.scanners;
