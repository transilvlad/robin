package com.mimecast.robin.scanners.rbl;

import java.util.Collections;
import java.util.List;

/**
 * Result of an RBL check.
 *
 * <p>This class represents the result of checking an IP address against a single RBL provider.
 * It includes information about whether the IP is listed, the RBL provider used,
 * and any response records returned by the RBL lookup.
 */
public class RblResult {
    private final String ip;
    private final String rblProvider;
    private final boolean listed;
    private final List<String> responseRecords;

    /**
     * Constructor.
     *
     * @param ip              The IP address that was checked
     * @param rblProvider     The RBL provider that was queried
     * @param listed          Whether the IP is listed in this RBL
     * @param responseRecords The A records returned by the RBL provider (if any)
     */
    public RblResult(String ip, String rblProvider, boolean listed, List<String> responseRecords) {
        this.ip = ip;
        this.rblProvider = rblProvider;
        this.listed = listed;
        this.responseRecords = responseRecords != null
                ? Collections.unmodifiableList(responseRecords)
                : Collections.emptyList();
    }

    /**
     * Get the IP address that was checked.
     *
     * @return The IP address
     */
    public String getIp() {
        return ip;
    }

    /**
     * Get the RBL provider that was queried.
     *
     * @return The RBL provider domain
     */
    public String getRblProvider() {
        return rblProvider;
    }

    /**
     * Check if the IP is listed in this RBL.
     *
     * @return true if the IP is listed, false otherwise
     */
    public boolean isListed() {
        return listed;
    }

    /**
     * Get the A records returned by the RBL provider.
     *
     * @return List of A records as strings
     */
    public List<String> getResponseRecords() {
        return responseRecords;
    }

    /**
     * Get a string representation of this result.
     *
     * @return A string representation
     */
    @Override
    public String toString() {
        return "RblResult{" +
                "ip='" + ip + '\'' +
                ", rblProvider='" + rblProvider + '\'' +
                ", listed=" + listed +
                ", responseRecords=" + responseRecords +
                '}';
    }
}
