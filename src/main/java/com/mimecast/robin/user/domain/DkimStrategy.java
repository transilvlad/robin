package com.mimecast.robin.user.domain;

/**
 * Key management strategy for a domain's DKIM configuration.
 */
public enum DkimStrategy {
    MANUAL,
    AUTO,
    DUAL_ALGO,
    SERVICE_SCOPED
}
