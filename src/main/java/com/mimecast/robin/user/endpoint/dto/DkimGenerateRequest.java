package com.mimecast.robin.user.endpoint.dto;

/**
 * Request payload for DKIM key generation and rotation.
 */
public class DkimGenerateRequest {
    private String selector;
    private String algorithm;
    private String strategy;
    private String serviceTag;
    private String domain;
    private Boolean testMode;

    public String getSelector() {
        return selector;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getStrategy() {
        return strategy;
    }

    public String getServiceTag() {
        return serviceTag;
    }

    public String getDomain() {
        return domain;
    }

    public Boolean getTestMode() {
        return testMode;
    }
}
