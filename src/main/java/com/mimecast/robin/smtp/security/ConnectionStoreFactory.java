package com.mimecast.robin.smtp.security;

import com.mimecast.robin.config.server.DistributedRateConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Factory for creating {@link ConnectionStore} implementations.
 *
 * <p>Returns a {@link RedisConnectionStore} when distributed rate limiting is enabled
 * in the configuration, otherwise returns a {@link LocalConnectionStore}.
 */
public final class ConnectionStoreFactory {
    private static final Logger log = LogManager.getLogger(ConnectionStoreFactory.class);

    private ConnectionStoreFactory() {
    }

    /**
     * Creates the appropriate {@link ConnectionStore} based on configuration.
     *
     * @param config Distributed rate limiting configuration.
     * @return A {@link RedisConnectionStore} if enabled, otherwise a {@link LocalConnectionStore}.
     */
    public static ConnectionStore create(DistributedRateConfig config) {
        if (config != null && config.isEnabled()) {
            log.info("Distributed rate limiting enabled — using Redis connection store");
            return new RedisConnectionStore(config);
        }
        log.info("Distributed rate limiting disabled — using local connection store");
        return new LocalConnectionStore();
    }
}
