HashiCorp Vault Integration
============================

Overview
--------

This guide provides detailed instructions on how to integrate HashiCorp Vault secrets management.
The Vault integration allows to securely fetch secrets like passwords, API keys, and certificates from a centralized Vault server
instead of storing them in configuration files.

Configuration
-------------

The Vault configuration has been added to your `server.json5` file:

```json5
vault: {
  // Enable or disable Vault integration (default: false).
  enabled: false,

  // Vault server address (e.g., "https://vault.example.com:8200").
  address: "https://vault.example.com:8200",

  // Vault authentication token or path to token file.
  token: "",

  // Vault namespace (optional, for Vault Enterprise).
  namespace: "",

  // Skip TLS certificate verification (use only in development).
  skipTlsVerification: false,

  // Connection timeout in seconds (default: 30).
  connectTimeout: 30,

  // Read timeout in seconds (default: 30).
  readTimeout: 30,

  // Write timeout in seconds (default: 30).
  writeTimeout: 30
}
```

### Configuration Options Explained

- **`enabled`**: Set to `true` to enable Vault integration.
- **`address`**: Your Vault server URL (must include protocol and port).
- **`token`**: Either the Vault token directly or a path to a file containing the token.
- **`namespace`**: (Optional) For Vault Enterprise multi-tenancy.
- **`skipTlsVerification`**: Set to `true` only in development environments to skip TLS verification.
- **`connectTimeout`**, **`readTimeout`**, **`writeTimeout`**: Timeout values in seconds for different operations.

## Security Best Practices

### 1. Token Management

**Store tokens in files, not in configuration:**

```json5
vault: {
  enabled: true,
  address: "https://vault.example.com:8200",
  token: "/usr/local/robin/secrets/vault-token",  // File path.
  // ...
}
```

Create the token file:
```bash
echo "s.your-vault-token-here" > /usr/local/robin/secrets/vault-token
chmod 600 /usr/local/robin/secrets/vault-token
```

### 2. TLS Configuration

**Never disable TLS verification in production:**

```json5
vault: {
  enabled: true,
  skipTlsVerification: false,  // ALWAYS false in production.
  // ...
}
```

### 3. Token Rotation

Implement token rotation by:
1. Using Vault's renewable tokens
2. Periodically updating the token file
3. Restarting the application or reloading configuration

### 4. Least Privilege

Configure Vault policies to grant minimal required permissions:

```hcl
# Example Vault policy for Robin
path "secret/data/robin/*" {
  capabilities = ["read"]
}

path "secret/metadata/robin/*" {
  capabilities = ["list"]
}
```
