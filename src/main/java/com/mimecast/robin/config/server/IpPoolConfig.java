package com.mimecast.robin.config.server;

import com.mimecast.robin.config.BasicConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * IP pool configuration.
 *
 * <p>Parses the {@code ipPools} list from the relay config map.
 * Each pool has a name, an optional {@code default} flag, and a list of address entries with weights.
 */
public class IpPoolConfig extends BasicConfig {

    /**
     * Constructs a new IpPoolConfig instance.
     *
     * @param map Configuration map (relay config map).
     */
    @SuppressWarnings("rawtypes")
    public IpPoolConfig(Map map) {
        super(map != null ? map : Collections.emptyMap());
    }

    /**
     * Returns the list of configured IP pools.
     *
     * @return List of PoolEntry instances; empty list if none configured.
     */
    @SuppressWarnings("unchecked")
    public List<PoolEntry> getPools() {
        List<?> raw = getListProperty("ipPools");
        if (raw.isEmpty()) return Collections.emptyList();

        List<PoolEntry> pools = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map) {
                pools.add(new PoolEntry((Map<String, Object>) item));
            }
        }
        return pools;
    }

    /**
     * An IP pool with a name, default flag, and address list.
     */
    public static class PoolEntry {
        private final String name;
        private final boolean isDefault;
        private final List<AddressEntry> addresses;

        /**
         * Constructs a PoolEntry from a config map.
         *
         * @param map Pool config map.
         */
        @SuppressWarnings("unchecked")
        public PoolEntry(Map<String, Object> map) {
            this.name = map.containsKey("name") ? (String) map.get("name") : "";
            this.isDefault = Boolean.TRUE.equals(map.get("default"));

            List<AddressEntry> addrs = new ArrayList<>();
            Object addrList = map.get("addresses");
            if (addrList instanceof List) {
                for (Object item : (List<?>) addrList) {
                    if (item instanceof Map) {
                        addrs.add(new AddressEntry((Map<String, Object>) item));
                    }
                }
            }
            this.addresses = Collections.unmodifiableList(addrs);
        }

        public String getName() { return name; }
        public boolean isDefault() { return isDefault; }
        public List<AddressEntry> getAddresses() { return addresses; }
    }

    /**
     * A bind address with an associated selection weight.
     */
    public static class AddressEntry {
        private final String ip;
        private final int weight;

        /**
         * Constructs an AddressEntry from a config map.
         * Weight defaults to 1 if absent or not a number.
         *
         * @param map Address config map.
         */
        public AddressEntry(Map<String, Object> map) {
            this.ip = map.containsKey("ip") ? (String) map.get("ip") : "";
            Object w = map.get("weight");
            this.weight = w instanceof Number ? ((Number) w).intValue() : 1;
        }

        public String getIp() { return ip; }
        public int getWeight() { return weight; }
    }
}
