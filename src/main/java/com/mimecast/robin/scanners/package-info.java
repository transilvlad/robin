/**
 * Deals with scanning emails for SPAM, viruses and other potential threats.
 *
 * <p>Robin can be configured to scan emails for SPAM using Rspamd.
 * <br>Rspamd integration is configured in the {@code cfg/rspamd.json5} file.
 * <br>The {@link com.mimecast.robin.scanners.RspamdClient} provides spam/phishing detection capabilities.
 *
 * <p>Robin can be configured to scan emails for viruses using ClamAV.
 * <br>ClamAV integration is configured in the {@code cfg/clamav.json5} file.
 * <br>The {@link com.mimecast.robin.scanners.ClamAVClient} provides virus detection capabilities.
 *
 * <h2>Scan Results Aggregation:</h2>
 * <p>The {@link com.mimecast.robin.smtp.MessageEnvelope} class aggregates scan results from multiple
 * <br>security scanners in a thread-safe manner. Scan results from both Rspamd and ClamAV
 * <br>are stored in the envelope and can be accessed for logging, decision making, or assertions.
 *
 * <p>Each scan result contains:
 * <ul>
 *     <li><b>Rspamd</b> - scanner name, spam score, spam flag, and symbol details</li>
 *     <li><b>ClamAV</b> - scanner name, infected flag, virus names, and part information</li>
 * </ul>
 *
 * <p>Results are stored using thread-safe collections and automatically filtered for null/empty entries.
 * <br>The scan results can be accessed via {@code envelope.getScanResults()}.
 *
 * @see com.mimecast.robin.scanners.RspamdClient
 * @see com.mimecast.robin.scanners.ClamAVClient
 * @see com.mimecast.robin.smtp.MessageEnvelope#getScanResults()
 * @see com.mimecast.robin.smtp.MessageEnvelope#addScanResult(java.util.Map)
 */
package com.mimecast.robin.scanners;
