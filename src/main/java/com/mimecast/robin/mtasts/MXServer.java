package com.mimecast.robin.mtasts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Server entry for a route, keeping its host, priority and the domains using it.
 */
public class MXServer {
    private final String host;
    private final int priority;
    private final List<String> domains = new ArrayList<>();

    /**
     * Constructs an MXServer with the given host and priority.
     *
     * @param host     server host
     * @param priority server priority
     */
    public MXServer(String host, int priority) {
        this.host = Objects.requireNonNull(host, "host");
        this.priority = priority;
    }

    /**
     * Gets the server host.
     */
    public String getHost() {
        return host;
    }

    /**
     * Gets the server priority.
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Gets the list of domains associated with this server.
     *
     * @return Unmodifiable list of domains.
     */
    public List<String> getDomains() {
        return Collections.unmodifiableList(domains);
    }

    /**
     * Adds a domain to this server's domain list.
     * Package-private so other classes in the package (e.g. MXRoute) can call it.
     */
    void addDomain(String domain) {
        if (domain == null || domain.isEmpty()) return;
        if (!domains.contains(domain)) {
            domains.add(domain);
        }
    }

    /**
     * String representation of the MXServer.
     */
    @Override
    public String toString() {
        return "Server{" +
                "host='" + host + '\'' +
                ", priority=" + priority +
                ", domains=" + domains +
                '}';
    }
}
