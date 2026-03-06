package com.mimecast.robin.queue.relay;

import com.mimecast.robin.config.server.IpPoolConfig;
import com.mimecast.robin.smtp.session.Session;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IpPoolSelector.
 */
class IpPoolSelectorTest {

    private IpPoolConfig buildConfig(List<Map<String, Object>> pools) {
        Map<String, Object> map = new HashMap<>();
        if (pools != null) {
            map.put("ipPools", pools);
        }
        return new IpPoolConfig(map);
    }

    private Map<String, Object> poolEntry(String name, boolean isDefault, List<Map<String, Object>> addresses) {
        Map<String, Object> pool = new HashMap<>();
        pool.put("name", name);
        pool.put("default", isDefault);
        pool.put("addresses", addresses);
        return pool;
    }

    private Map<String, Object> addr(String ip, double weight) {
        Map<String, Object> m = new HashMap<>();
        m.put("ip", ip);
        m.put("weight", weight);
        return m;
    }

    @Test
    void selectAddressReturnsNullWhenNoPoolsConfigured() {
        IpPoolSelector selector = new IpPoolSelector(buildConfig(null));
        assertNull(selector.selectAddress("default"));
    }

    @Test
    void selectAddressDistributesProportionallyToWeightsOver15Calls() {
        List<Map<String, Object>> addresses = List.of(
                addr("10.0.0.1", 10),
                addr("10.0.0.2", 5)
        );
        IpPoolConfig config = buildConfig(List.of(poolEntry("default", true, addresses)));
        IpPoolSelector selector = new IpPoolSelector(config);

        int count1 = 0, count2 = 0;
        for (int i = 0; i < 15; i++) {
            String ip = selector.selectAddress("default");
            assertNotNull(ip);
            if ("10.0.0.1".equals(ip)) count1++;
            else if ("10.0.0.2".equals(ip)) count2++;
        }
        assertEquals(10, count1);
        assertEquals(5, count2);
    }

    @Test
    void selectPoolKeyReturnsDefaultPoolName() {
        IpPoolConfig config = buildConfig(List.of(
                poolEntry("custom", false, List.of()),
                poolEntry("primary", true, List.of())
        ));
        IpPoolSelector selector = new IpPoolSelector(config);
        assertEquals("primary", selector.selectPoolKey(new Session()));
    }

    @Test
    void selectPoolKeyReturnsFallbackWhenNoPoolsConfigured() {
        IpPoolSelector selector = new IpPoolSelector(buildConfig(null));
        assertEquals("default", selector.selectPoolKey(new Session()));
    }

    @Test
    void selectAddressReturnsNullWhenPoolNotFound() {
        IpPoolConfig config = buildConfig(List.of(
                poolEntry("pool-a", true, List.of(addr("10.0.0.1", 1)))
        ));
        IpPoolSelector selector = new IpPoolSelector(config);
        assertNull(selector.selectAddress("nonexistent"));
    }
}
