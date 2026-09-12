package com.mimecast.robin.scanners.port;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * Result of a port + TLS probe for a single host:port.
 */
public class PortTlsResult {

    public enum TlsStatus { ENABLED, DISABLED, ERROR }

    private final String host;
    private final int port;
    private final boolean open;
    private final TlsStatus tlsStatus;
    private final String banner;
    private final List<String> extensions;
    private final String certSubject;
    private final String certIssuer;
    private final LocalDate certExpiry;
    private final int daysUntilExpiry;
    private final String negotiatedProtocol;
    private final int certKeyBits;
    private final boolean hostnameMatch;
    private final String errorMessage;

    private PortTlsResult(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.open = b.open;
        this.tlsStatus = b.tlsStatus;
        this.banner = b.banner;
        this.extensions = Collections.unmodifiableList(b.extensions);
        this.certSubject = b.certSubject;
        this.certIssuer = b.certIssuer;
        this.certExpiry = b.certExpiry;
        this.daysUntilExpiry = b.daysUntilExpiry;
        this.negotiatedProtocol = b.negotiatedProtocol;
        this.certKeyBits = b.certKeyBits;
        this.hostnameMatch = b.hostnameMatch;
        this.errorMessage = b.errorMessage;
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public boolean isOpen() { return open; }
    public TlsStatus getTlsStatus() { return tlsStatus; }
    public String getBanner() { return banner; }
    public List<String> getExtensions() { return extensions; }
    public String getCertSubject() { return certSubject; }
    public String getCertIssuer() { return certIssuer; }
    public LocalDate getCertExpiry() { return certExpiry; }
    public int getDaysUntilExpiry() { return daysUntilExpiry; }
    public String getNegotiatedProtocol() { return negotiatedProtocol; }
    public int getCertKeyBits() { return certKeyBits; }
    public boolean isHostnameMatch() { return hostnameMatch; }
    public String getErrorMessage() { return errorMessage; }

    /** True if cert expires within 30 days or is already expired. */
    public boolean isCertExpiringSoon() {
        return tlsStatus == TlsStatus.ENABLED && certExpiry != null && daysUntilExpiry <= 30;
    }

    public static Builder builder(String host, int port) {
        return new Builder(host, port);
    }

    public static class Builder {
        private final String host;
        private final int port;
        private boolean open;
        private TlsStatus tlsStatus = TlsStatus.DISABLED;
        private String banner;
        private List<String> extensions = List.of();
        private String certSubject;
        private String certIssuer;
        private LocalDate certExpiry;
        private int daysUntilExpiry = Integer.MAX_VALUE;
        private String negotiatedProtocol;
        private int certKeyBits;
        private boolean hostnameMatch;
        private String errorMessage;

        private Builder(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public Builder open(boolean v) { this.open = v; return this; }
        public Builder tlsStatus(TlsStatus v) { this.tlsStatus = v; return this; }
        public Builder banner(String v) { this.banner = v; return this; }
        public Builder extensions(List<String> v) { this.extensions = v != null ? List.copyOf(v) : List.of(); return this; }
        public Builder certSubject(String v) { this.certSubject = v; return this; }
        public Builder certIssuer(String v) { this.certIssuer = v; return this; }
        public Builder certExpiry(LocalDate v) { this.certExpiry = v; return this; }
        public Builder daysUntilExpiry(int v) { this.daysUntilExpiry = v; return this; }
        public Builder negotiatedProtocol(String v) { this.negotiatedProtocol = v; return this; }
        public Builder certKeyBits(int v) { this.certKeyBits = v; return this; }
        public Builder hostnameMatch(boolean v) { this.hostnameMatch = v; return this; }
        public Builder error(String v) { this.errorMessage = v; return this; }
        public PortTlsResult build() { return new PortTlsResult(this); }
    }
}
