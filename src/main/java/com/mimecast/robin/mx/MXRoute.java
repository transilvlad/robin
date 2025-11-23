package com.mimecast.robin.mx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * MXRoute groups domains that share the same ordered MX server list.
 * It keeps the route hash, the ordered servers and the domains using this route.
 */
public class MXRoute {

    // SHA-256 of canonical ordered MX list.
    private final String hash;

    // Domains that share this route.
    private final List<String> domains;

    // Ordered by priority then name.
    private final List<MXServer> servers;

    /**
     * Constructs an MXRoute with the given hash and servers.
     *
     * @param hash    route hash
     * @param servers ordered list of servers
     */
    public MXRoute(String hash, List<MXServer> servers) {
        this.hash = Objects.requireNonNull(hash, "hash");
        this.servers = new ArrayList<>(Objects.requireNonNull(servers, "servers"));
        this.domains = new ArrayList<>();
    }

    /**
     * Gets the route hash.
     */
    public String getHash() {
        return hash;
    }

    /**
     * Gets the list of servers for this route.
     *
     * @return Unmodifiable list of servers.
     */
    public List<MXServer> getServers() {
        return Collections.unmodifiableList(servers);
    }

    /**
     * Gets the list of server IP addresses for this route.
     *
     * @return Unmodifiable list of IP addresses.
     */
    public List<String> getIpAddresses() {
        return this.servers.stream().flatMap(server -> server.getIpAddresses().stream()).toList();
    }

    /**
     * Gets the list of domains associated with this route.
     *
     * @return Unmodifiable list of domains.
     */
    public List<String> getDomains() {
        return Collections.unmodifiableList(domains);
    }

    /**
     * Adds a domain to this route and associates it with each server in the route.
     */
    public void addDomain(String domain) {
        if (domain == null || domain.isEmpty()) return;
        if (!domains.contains(domain)) {
            domains.add(domain);
        }

        for (MXServer s : servers) {
            s.addDomain(domain);
        }
    }

    /**
     * Equality based on route hash.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MXRoute mxRoute = (MXRoute) o;
        return hash.equals(mxRoute.hash);
    }

    /**
     * Hash code based on route hash.
     */
    @Override
    public int hashCode() {
        return hash.hashCode();
    }

    /**
     * String representation of the MXRoute.
     */
    @Override
    public String toString() {
        return "MXRoute{" +
                "hash='" + hash + '\'' +
                ", servers=" + servers +
                ", domains=" + domains +
                '}';
    }
}
