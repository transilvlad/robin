package com.mimecast.robin.scanners.rbl;

import java.util.List;

/**
 * Result of a domain blocklist (DBL) lookup for a single domain + provider pair.
 */
public class DblResult {

    private final String domain;
    private final String dblProvider;
    private final boolean listed;
    private final List<String> responseRecords;

    public DblResult(String domain, String dblProvider, boolean listed, List<String> responseRecords) {
        this.domain = domain;
        this.dblProvider = dblProvider;
        this.listed = listed;
        this.responseRecords = responseRecords;
    }

    public String getDomain() { return domain; }
    public String getDblProvider() { return dblProvider; }
    public boolean isListed() { return listed; }
    public List<String> getResponseRecords() { return responseRecords; }
}
