package com.mimecast.robin.user.domain;

import java.time.OffsetDateTime;

/**
 * Represents a managed DKIM keypair stored in {@code dkim_keys}.
 */
public class DkimKey {

    private Long id;
    private String domain;
    private String selector;
    private String algorithm;
    private String privateKeyEnc;
    private String publicKey;
    private String dnsRecordValue;
    private DkimKeyStatus status;
    private boolean testMode = true;
    private DkimStrategy strategy;
    private String serviceTag;
    private Long pairedKeyId;
    private OffsetDateTime rotationScheduledAt;
    private OffsetDateTime publishedAt;
    private OffsetDateTime activatedAt;
    private OffsetDateTime retireAfter;
    private OffsetDateTime retiredAt;
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getSelector() {
        return selector;
    }

    public void setSelector(String selector) {
        this.selector = selector;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public String getPrivateKeyEnc() {
        return privateKeyEnc;
    }

    public void setPrivateKeyEnc(String privateKeyEnc) {
        this.privateKeyEnc = privateKeyEnc;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getDnsRecordValue() {
        return dnsRecordValue;
    }

    public void setDnsRecordValue(String dnsRecordValue) {
        this.dnsRecordValue = dnsRecordValue;
    }

    public DkimKeyStatus getStatus() {
        return status;
    }

    public void setStatus(DkimKeyStatus status) {
        this.status = status;
    }

    public boolean isTestMode() {
        return testMode;
    }

    public void setTestMode(boolean testMode) {
        this.testMode = testMode;
    }

    public DkimStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(DkimStrategy strategy) {
        this.strategy = strategy;
    }

    public String getServiceTag() {
        return serviceTag;
    }

    public void setServiceTag(String serviceTag) {
        this.serviceTag = serviceTag;
    }

    public Long getPairedKeyId() {
        return pairedKeyId;
    }

    public void setPairedKeyId(Long pairedKeyId) {
        this.pairedKeyId = pairedKeyId;
    }

    public OffsetDateTime getRotationScheduledAt() {
        return rotationScheduledAt;
    }

    public void setRotationScheduledAt(OffsetDateTime rotationScheduledAt) {
        this.rotationScheduledAt = rotationScheduledAt;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(OffsetDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public OffsetDateTime getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(OffsetDateTime activatedAt) {
        this.activatedAt = activatedAt;
    }

    public OffsetDateTime getRetireAfter() {
        return retireAfter;
    }

    public void setRetireAfter(OffsetDateTime retireAfter) {
        this.retireAfter = retireAfter;
    }

    public OffsetDateTime getRetiredAt() {
        return retiredAt;
    }

    public void setRetiredAt(OffsetDateTime retiredAt) {
        this.retiredAt = retiredAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
