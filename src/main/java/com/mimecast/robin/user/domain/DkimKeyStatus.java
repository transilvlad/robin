package com.mimecast.robin.user.domain;

/**
 * Lifecycle states for a managed DKIM key.
 *
 * <p>Valid transitions:
 * <pre>
 *   PENDING_PUBLISH → ACTIVE → ROTATING_OUT → RETIRED
 *                                    ↓
 *                                 REVOKED  (from any state, emergency)
 * </pre>
 */
public enum DkimKeyStatus {
    PENDING_PUBLISH,
    ACTIVE,
    ROTATING_OUT,
    RETIRED,
    REVOKED
}
