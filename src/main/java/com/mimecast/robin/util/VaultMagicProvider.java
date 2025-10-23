package com.mimecast.robin.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vault magic provider for integrating HashiCorp Vault secrets with Magic variables.
 *
 * <p>This utility bridges Vault secrets with the Magic variable system, allowing
 * secrets to be referenced using the standard magic variable syntax: {$vaultSecretName}
 *
 * <p>Secrets are cached in memory after first retrieval to improve performance.
 * The cache can be cleared to force re-fetching of secrets.
 *
 * <p>Example usage:
 * <pre>
 * // Initialize with VaultClient
 * VaultMagicProvider.initialize(vaultClient);
 *
 * // Register Vault paths for secrets
 * VaultMagicProvider.registerSecretPath("secret/data/robin/passwords");
 *
 * // Use in magic variables
 * String config = "password={$vault.keystore.password}";
 * String replaced = Magic.magicReplace(config, session);
 * </pre>
 */
public class VaultMagicProvider {
    private static final Logger log = LogManager.getLogger(VaultMagicProvider.class);

    private static VaultClient vaultClient;
    private static final Map<String, String> secretsCache = new ConcurrentHashMap<>();
    private static boolean initialized = false;

    /**
     * Private constructor to prevent instantiation.
     */
    private VaultMagicProvider() {
        // Utility class.
    }

    /**
     * Initializes the Vault magic provider with a VaultClient.
     *
     * @param client VaultClient instance.
     */
    public static synchronized void initialize(VaultClient client) {
        if (client == null) {
            log.warn("VaultMagicProvider initialized with null client");
            return;
        }

        vaultClient = client;
        initialized = true;

        if (client.isEnabled()) {
            log.info("VaultMagicProvider initialized and enabled");
        } else {
            log.info("VaultMagicProvider initialized but Vault is disabled");
        }
    }

    /**
     * Checks if Vault magic provider is initialized and enabled.
     *
     * @return true if initialized and Vault is enabled.
     */
    public static boolean isEnabled() {
        return initialized && vaultClient != null && vaultClient.isEnabled();
    }


    /**
     * Gets a secret value by magic variable name.
     * Automatically resolves the Vault path from the magic variable name and fetches on-demand.
     *
     * @param magicVariableName Magic variable name (e.g., "vault.keystore.password").
     * @return Secret value, or null if not found.
     */
    public static String getSecret(String magicVariableName) {
        if (!isEnabled()) {
            return null;
        }

        // Check cache first.
        String cached = secretsCache.get(magicVariableName);
        if (cached != null) {
            return cached;
        }

        // Auto-resolve path from magic variable name (e.g., vault.keystore.password).
        // Format: prefix.path1.path2...pathN.keyname.
        // Converts to: secret/data/prefix/path1/path2/.../pathN with key: keyname.
        String secret = autoResolveAndFetch(magicVariableName);
        if (secret != null) {
            secretsCache.put(magicVariableName, secret);
            log.debug("Auto-resolved and cached Vault secret: {}", magicVariableName);
            return secret;
        }

        return null;
    }

    /**
     * Auto-resolves Vault path from magic variable name and fetches the secret.
     * Supports multiple path structures:
     * - vault.keyname -> secret/data/vault with key "keyname"
     * - vault.keystore.password -> secret/data/vault/keystore with key "password"
     * - vault.api.key -> secret/data/vault/api with key "key"
     *
     * @param magicVariableName Magic variable name.
     * @return Secret value, or null if not found.
     */
    private static String autoResolveAndFetch(String magicVariableName) {
        String[] parts = magicVariableName.split("\\.");
        if (parts.length < 2) {
            return null; // Need at least prefix.keyname.
        }

        // Last part is the key name.
        String keyName = parts[parts.length - 1];

        // Build path from remaining parts: secret/data/part1/part2/.../partN-1.
        StringBuilder pathBuilder = new StringBuilder("secret/data");
        for (int i = 0; i < parts.length - 1; i++) {
            pathBuilder.append("/").append(parts[i]);
        }
        String vaultPath = pathBuilder.toString();

        try {
            String secret = vaultClient.getSecret(vaultPath, keyName);
            if (secret != null) {
                log.debug("Auto-resolved secret from path: {} key: {}", vaultPath, keyName);
                return secret;
            }
        } catch (VaultClient.VaultException e) {
            log.debug("Auto-resolve failed for path: {} key: {} - {}", vaultPath, keyName, e.getMessage());
        }

        return null;
    }

    /**
     * Clears the secrets cache, forcing re-fetch on next access.
     */
    public static void clearCache() {
        secretsCache.clear();
        log.info("Vault secrets cache cleared");
    }
}
