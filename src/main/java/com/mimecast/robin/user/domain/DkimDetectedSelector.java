package com.mimecast.robin.user.domain;

import java.time.Instant;

/**
 * Represents a DKIM selector detected in DNS for a domain.
 */
public class DkimDetectedSelector {
    private Long id;
    private String domain;
    private String selector;
    private String publicKeyDns;
    private String algorithm;
    private Boolean testMode;
    private boolean revoked;
    private Instant detectedAt;

    public DkimDetectedSelector() {
    }

    public Long getId() {
        return id;
    }

    public DkimDetectedSelector setId(Long id) {
        this.id = id;
        return this;
    }

    public String getDomain() {
        return domain;
    }

    public DkimDetectedSelector setDomain(String domain) {
        this.domain = domain;
        return this;
    }

    public String getSelector() {
        return selector;
    }

    public DkimDetectedSelector setSelector(String selector) {
        this.selector = selector;
        return this;
    }

    public String getPublicKeyDns() {
        return publicKeyDns;
    }

    public DkimDetectedSelector setPublicKeyDns(String publicKeyDns) {
        this.publicKeyDns = publicKeyDns;
        return this;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public DkimDetectedSelector setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
        return this;
    }

    public Boolean getTestMode() {
        return testMode;
    }

    public DkimDetectedSelector setTestMode(Boolean testMode) {
        this.testMode = testMode;
        return this;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public DkimDetectedSelector setRevoked(boolean revoked) {
        this.revoked = revoked;
        return this;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public DkimDetectedSelector setDetectedAt(Instant detectedAt) {
        this.detectedAt = detectedAt;
        return this;
    }

    public String getPublicKeyPreview() {
        if (publicKeyDns == null || publicKeyDns.isEmpty()) {
            return "";
        }
        return publicKeyDns.length() > 20 ? publicKeyDns.substring(0, 20) + "..." : publicKeyDns;
    }
}
