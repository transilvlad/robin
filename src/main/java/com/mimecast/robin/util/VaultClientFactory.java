package com.mimecast.robin.util;

import com.mimecast.robin.config.server.ServerConfig;
import com.mimecast.robin.config.server.VaultConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;

/**
 * Factory for creating VaultClient instances from ServerConfig.
 *
 * <p>This utility simplifies the creation of VaultClient instances by reading
 * configuration from the server configuration.
 *
 * <p>Example usage:
 * <pre>
 * ServerConfig config = new ServerConfig("cfg/server.json5");
 * VaultClient vaultClient = VaultClientFactory.createFromConfig(config);
 *
 * if (vaultClient.isEnabled()) {
 *     String secret = vaultClient.getSecret("secret/data/myapp/config", "password");
 * }
 * </pre>
 */
public class VaultClientFactory {
    private static final Logger log = LogManager.getLogger(VaultClientFactory.class);

    /**
     * Private constructor to prevent instantiation.
     */
    private VaultClientFactory() {
        // Utility class
    }

    /**
     * Creates a VaultClient from ServerConfig.
     *
     * @param serverConfig ServerConfig instance.
     * @return VaultClient instance.
     */
    public static VaultClient createFromConfig(ServerConfig serverConfig) {
        VaultConfig vaultConfig = serverConfig.getVault();

        VaultClient.Builder builder = new VaultClient.Builder()
                .withEnabled(vaultConfig.isEnabled());

        if (vaultConfig.isEnabled()) {
            String token = resolveSecret(vaultConfig.getToken());

            builder.withAddress(vaultConfig.getAddress())
                    .withToken(token)
                    .withConnectTimeout(vaultConfig.getConnectTimeout())
                    .withReadTimeout(vaultConfig.getReadTimeout())
                    .withWriteTimeout(vaultConfig.getWriteTimeout())
                    .withSkipTlsVerification(vaultConfig.isSkipTlsVerification());

            if (vaultConfig.getNamespace() != null && !vaultConfig.getNamespace().isEmpty()) {
                builder.withNamespace(vaultConfig.getNamespace());
            }

            log.info("Vault integration enabled with address: {}", vaultConfig.getAddress());

            if (vaultConfig.isSkipTlsVerification()) {
                log.warn("Vault TLS verification is disabled - use only in development!");
            }
        } else {
            log.debug("Vault integration is disabled");
        }

        return builder.build();
    }

    /**
     * Resolves a secret value that may be a file path or direct value.
     *
     * @param value Value or file path.
     * @return Resolved secret value.
     */
    private static String resolveSecret(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        // Check if it's a file path
        if (PathUtils.isFile(value)) {
            try {
                String fileContent = PathUtils.readFile(value, StandardCharsets.UTF_8);
                // Trim whitespace and newlines
                return fileContent.trim();
            } catch (Exception e) {
                log.warn("Failed to read secret from file: {}", value, e);
                return value;
            }
        }

        return value;
    }
}
