package com.mimecast.robin.mx;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.mx.assets.DnsRecord;
import com.mimecast.robin.mx.client.XBillDnsRecordClient;
import org.xbill.DNS.Address;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Type;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.*;

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

    public List<String> getIpAddresses() {
        if (Address.isDottedQuad(host) || host.contains(":")) {
            return List.of(host);
        }

        List<String> routeable = resolveRouteableAddresses(host, new HashSet<>(), 0);
        if (routeable.isEmpty()) return Collections.emptyList();

        String preference = "ipv4_first";
        try {
            preference = Config.getServer().getDnsConfig().getMxAddressFamilyPreference();
        } catch (Exception ignored) {
            // Keep default if config is not initialized in this execution path.
        }

        List<InetAddress> resolved = new ArrayList<>();
        for (String ip : routeable) {
            try {
                resolved.add(InetAddress.getByName(ip));
            } catch (Exception ignored) {
                // Keep any non-IP values out of routing candidate list.
            }
        }

        if (resolved.isEmpty()) {
            return new ArrayList<>(new LinkedHashSet<>(routeable));
        }

        if ("ipv6_first".equals(preference)) {
            resolved.sort(Comparator.comparingInt(a -> (a instanceof Inet6Address) ? 0 : 1));
        } else if ("ipv4_first".equals(preference)) {
            resolved.sort(Comparator.comparingInt(a -> (a instanceof Inet6Address) ? 1 : 0));
        }

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (InetAddress addr : resolved) {
            unique.add(addr.getHostAddress());
        }
        return new ArrayList<>(unique);
    }

    private List<String> resolveRouteableAddresses(String hostName, Set<String> seen, int depth) {
        if (hostName == null || hostName.isBlank() || depth > 8) {
            return Collections.emptyList();
        }

        String normalized = hostName.endsWith(".")
                ? hostName.substring(0, hostName.length() - 1)
                : hostName;
        String key = normalized.toLowerCase(Locale.ROOT);
        if (!seen.add(key)) {
            return Collections.emptyList();
        }

        Optional<List<DnsRecord>> direct = new XBillDnsRecordClient().getARecords(normalized);
        if (direct.isPresent() && !direct.get().isEmpty()) {
            LinkedHashSet<String> ips = new LinkedHashSet<>();
            for (DnsRecord record : direct.get()) {
                if (record.getValue() != null && !record.getValue().isBlank()) {
                    ips.add(record.getValue());
                }
            }
            if (!ips.isEmpty()) {
                return new ArrayList<>(ips);
            }
        }

        try {
            Lookup lookup = new Lookup(normalized, Type.CNAME);
            Resolver resolver = Lookup.getDefaultResolver();
            if (resolver == null) resolver = new ExtendedResolver();
            lookup.setResolver(resolver);
            Record[] records = lookup.run();
            if (records != null) {
                for (Record record : records) {
                    if (record instanceof CNAMERecord cname) {
                        List<String> aliasResolved = resolveRouteableAddresses(cname.getTarget().toString(true), seen, depth + 1);
                        if (!aliasResolved.isEmpty()) {
                            return aliasResolved;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Not routeable through CNAME chain.
        }

        return Collections.emptyList();
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
