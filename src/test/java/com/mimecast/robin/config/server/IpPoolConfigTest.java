package com.mimecast.robin.config.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IpPoolConfig.
 */
class IpPoolConfigTest {

    @Test
    void getPoolsReturnsCorrectListFromMap() {
        Map<String, Object> addr1 = new HashMap<>();
        addr1.put("ip", "10.0.0.1");
        addr1.put("weight", 10.0);

        Map<String, Object> addr2 = new HashMap<>();
        addr2.put("ip", "10.0.0.2");
        addr2.put("weight", 5.0);

        Map<String, Object> pool = new HashMap<>();
        pool.put("name", "default");
        pool.put("default", true);
        pool.put("addresses", List.of(addr1, addr2));

        Map<String, Object> map = new HashMap<>();
        map.put("ipPools", List.of(pool));

        IpPoolConfig config = new IpPoolConfig(map);
        List<IpPoolConfig.PoolEntry> pools = config.getPools();

        assertEquals(1, pools.size());
        IpPoolConfig.PoolEntry entry = pools.get(0);
        assertEquals("default", entry.getName());
        assertTrue(entry.isDefault());
        assertEquals(2, entry.getAddresses().size());
        assertEquals("10.0.0.1", entry.getAddresses().get(0).getIp());
        assertEquals(10, entry.getAddresses().get(0).getWeight());
        assertEquals("10.0.0.2", entry.getAddresses().get(1).getIp());
        assertEquals(5, entry.getAddresses().get(1).getWeight());
    }

    @Test
    void getPoolsReturnsEmptyListWhenIpPoolsAbsent() {
        IpPoolConfig config = new IpPoolConfig(new HashMap<>());
        assertTrue(config.getPools().isEmpty());
    }

    @Test
    void getPoolsReturnsEmptyListWhenMapIsNull() {
        IpPoolConfig config = new IpPoolConfig(null);
        assertTrue(config.getPools().isEmpty());
    }

    @Test
    void addressEntryWeightDefaultsToOneWhenAbsent() {
        Map<String, Object> addr = new HashMap<>();
        addr.put("ip", "192.168.1.1");
        // no "weight" key

        Map<String, Object> pool = new HashMap<>();
        pool.put("name", "p1");
        pool.put("default", false);
        pool.put("addresses", List.of(addr));

        Map<String, Object> map = new HashMap<>();
        map.put("ipPools", List.of(pool));

        IpPoolConfig config = new IpPoolConfig(map);
        IpPoolConfig.AddressEntry entry = config.getPools().get(0).getAddresses().get(0);
        assertEquals(1, entry.getWeight());
        assertEquals("192.168.1.1", entry.getIp());
    }
}
