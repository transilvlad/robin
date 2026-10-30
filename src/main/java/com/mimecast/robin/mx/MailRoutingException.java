package com.mimecast.robin.mx;

/**
 * An explicit permanent or retryable failure to resolve a mail destination.
 */
public class MailRoutingException extends RuntimeException {
    private final boolean permanent;

    public MailRoutingException(String message, boolean permanent) {
        super(message);
        this.permanent = permanent;
    }

    public boolean isPermanent() {
        return permanent;
    }
}
