package com.mimecast.robin.mx.dane;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.mx.MailRoutingException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DANE (DNS-Based Authentication of Named Entities) checker for SMTP.
 * <p>This class checks for TLSA records that provide certificate association
 * <br>data for TLS authentication of mail servers.
 *
 * <p>DANE for SMTP uses TLSA records published at _25._tcp.&lt;mx-hostname&gt;
 * <br>to authenticate the TLS certificate presented by the mail server.
 *
 * @see <a href="https://tools.ietf.org/html/rfc6698">RFC 6698 - DANE</a>
 * @see <a href="https://tools.ietf.org/html/rfc7671">RFC 7671 - DANE Operations</a>
 * @see <a href="https://tools.ietf.org/html/rfc7672">RFC 7672 - DANE for SMTP</a>
 */
public class DaneChecker {
    private static final Logger log = LogManager.getLogger(DaneChecker.class);
    private static final int SMTP_PORT = 25;
    private static final String PROTOCOL = "tcp";

    /**
     * Uses AD only when an operator explicitly trusts the configured validating
     * resolver. Unauthenticated TLSA data must never become a trust anchor.
     */
    public static List<DaneRecord> checkAuthenticatedDane(String hostname) {
        if (!Config.getServer().getDnsConfig().isTrustAuthenticatedData()) {
            return List.of();
        }
        String normalized = hostname.endsWith(".") ? hostname.substring(0, hostname.length() - 1) : hostname;
        try {
            Message query = Message.newQuery(Record.newRecord(
                    Name.fromString("_25._tcp." + normalized, Name.root), Type.TLSA, DClass.IN));
            query.addRecord(new OPTRecord(1232, 0, 0, ExtendedFlags.DO), Section.ADDITIONAL);
            Message response = Lookup.getDefaultResolver().send(query);
            if (response.getRcode() != Rcode.NOERROR && response.getRcode() != Rcode.NXDOMAIN) {
                throw new MailRoutingException("TLSA validation lookup failed for " + normalized, false);
            }
            if (!response.getHeader().getFlag(Flags.AD)) {
                return List.of();
            }
            List<DaneRecord> records = new ArrayList<>();
            for (Record record : response.getSection(Section.ANSWER)) {
                if (record instanceof TLSARecord tlsa && tlsa.getName().equals(query.getQuestion().getName())
                        && (tlsa.getCertificateUsage() == 2 || tlsa.getCertificateUsage() == 3)
                        && tlsa.getSelector() <= 1 && tlsa.getMatchingType() <= 2) {
                    records.add(new DaneRecord(normalized, tlsa.getCertificateUsage(), tlsa.getSelector(),
                            tlsa.getMatchingType(), bytesToHex(tlsa.getCertificateAssociationData()),
                            tlsa.rdataToString()));
                }
            }
            return records;
        } catch (java.io.IOException e) {
            throw new MailRoutingException("TLSA validation lookup failed for " + normalized + ": " + e.getMessage(), false);
        }
    }

    /**
     * Check for DANE TLSA records for an MX hostname.
     * <p>Looks up TLSA records at _25._tcp.&lt;mx-hostname&gt;
     *
     * @param mxHostname The MX hostname to check for DANE support.
     * @return List of DANE records found, or empty list if none.
     */
    public static List<DaneRecord> checkDane(String mxHostname) {
        if (mxHostname == null || mxHostname.isEmpty()) {
            log.warn("MX hostname is null or empty, skipping DANE check");
            return Collections.emptyList();
        }

        // Remove trailing dot if present.
        if (mxHostname.endsWith(".")) {
            mxHostname = mxHostname.substring(0, mxHostname.length() - 1);
        }

        // Construct TLSA record name: _25._tcp.mx.example.com.
        String tlsaName = String.format("_%d._%s.%s", SMTP_PORT, PROTOCOL, mxHostname);

        log.debug("Checking DANE TLSA records for: {}", tlsaName);

        try {
            Lookup lookup = new Lookup(tlsaName, Type.TLSA);
            Record[] records = lookup.run();

            if (records == null || records.length == 0) {
                int result = lookup.getResult();
                if (result == Lookup.HOST_NOT_FOUND || result == Lookup.TYPE_NOT_FOUND) {
                    log.debug("No DANE TLSA records found for: {}", mxHostname);
                } else {
                    log.warn("DANE lookup failed for {} with result: {}", mxHostname, lookup.getErrorString());
                }
                return Collections.emptyList();
            }

            List<DaneRecord> daneRecords = new ArrayList<>();
            for (Record record : records) {
                if (record instanceof TLSARecord) {
                    TLSARecord tlsaRecord = (TLSARecord) record;

                    int usage = tlsaRecord.getCertificateUsage();
                    int selector = tlsaRecord.getSelector();
                    int matchingType = tlsaRecord.getMatchingType();
                    byte[] certData = tlsaRecord.getCertificateAssociationData();
                    String certHex = bytesToHex(certData);
                    String fullRecord = tlsaRecord.rdataToString();

                    DaneRecord daneRecord = new DaneRecord(
                            mxHostname,
                            usage,
                            selector,
                            matchingType,
                            certHex,
                            fullRecord
                    );

                    daneRecords.add(daneRecord);
                    log.debug("Found DANE record for {}: {}", mxHostname, daneRecord);
                }
            }

            return daneRecords;

        } catch (TextParseException e) {
            log.error("Invalid hostname format for DANE check: {}", mxHostname, e);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Error checking DANE for {}: {}", mxHostname, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Check for DANE records for multiple MX hostnames.
     *
     * @param mxHostnames List of MX hostnames to check.
     * @return List of all DANE records found across all hostnames.
     */
    public static List<DaneRecord> checkDaneForMxList(List<String> mxHostnames) {
        if (mxHostnames == null || mxHostnames.isEmpty()) {
            return Collections.emptyList();
        }

        List<DaneRecord> allRecords = new ArrayList<>();
        for (String mxHostname : mxHostnames) {
            List<DaneRecord> records = checkDane(mxHostname);
            allRecords.addAll(records);
        }

        return allRecords;
    }

    /**
     * Convert byte array to hex string.
     *
     * @param bytes Byte array.
     * @return Hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Check if a domain has DANE enabled (has any TLSA records).
     *
     * @param mxHostname The MX hostname to check.
     * @return True if DANE is enabled, false otherwise.
     */
    public static boolean isDaneEnabled(String mxHostname) {
        List<DaneRecord> records = checkDane(mxHostname);
        return !records.isEmpty();
    }
}
