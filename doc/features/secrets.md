# Secrets, magic and Local Secrets File

This document explains how to manage local secrets for Robin, shows an example secrets file you can copy and edit,
and provides a CLI run example that loads the secrets file via a JVM system property.

<span style="color:red">Do NOT commit real secrets into the repository!!!</span>

Robin magic will read secrets from system properties and populate them into the application configuration on runtime.
Use this mechanism to keep secrets out of version control. Use {$secretName} placeholders in your main configuration files and let Robin do the rest.
All configuration file secrets support magic variable substitution.

## Overview

- Keep secrets (API keys, passwords, keystore passwords, etc.) out of version control.
- Use an untracked, filesystem-local secrets file and point Robin at it using a JVM system property.
- The application supports reading properties from system properties; use `-D...` JVM options to provide the path to your secrets file.

## Example secrets file

Example contents (secrets.properties):

```
# Example secrets file for Robin.
# Copy this to a safe location (outside the repository) and edit values.
# Then start Robin with a system property pointing to the file, for example:
#   -Dsecrets.file=/usr/local/robin/cfg/secrets.properties

# Example entries (replace with your real secrets):
# Prometheus remote write credentials
bearerToken=avengers-assemble-token
basicAuthUser=
basicAuthPassword=

# Webhook credentials (if applicable)
authType=bearer
authValue=avengers-assemble-token

# TLS/SSL keystore password
keystorePassword=avengers
truststorePassword=assemble

# Do NOT commit real secrets into the source repository!!!
```

Adapt the keys to match the properties your deployment or plugins expect.
The above is a minimal illustrative example; remove or extend properties as required.

## CLI run example

Unix/Linux/macOS example (foundation command adjusted):

```sh
java -server -Xms256m -Xmx1024m \
  -Dlog4j.configurationFile=/usr/local/robin/cfg/log4j2.xml \
  -Dsecrets.file=/usr/local/robin/cfg/secrets.properties \
  -cp "/usr/local/robin/robin.jar:/usr/local/robin/lib/*" \
  com.mimecast.robin.Main --server /usr/local/robin/cfg/
```

Notes about the command:
- `-Dsecrets.file=...` is the JVM system property pointing to the secrets file on disk. Replace the path with your location.
- Keep the secrets file outside the repo and restrict access permissions on the file.

## Security best practices

- Don't commit secrets into the repository. Keep the example file in the repo but not real secrets.
- Use strict filesystem permissions on any local secrets file.
- Consider using an OS-level secret store (Key Vault, AWS Secrets Manager, HashiCorp Vault, Windows Credential Manager)
for production environments where possible.
- Avoid passing sensitive values directly on the command line; pointing to a file is preferable because some OSes may expose
the command line to other users.
- Regularly rotate secrets and update the local secrets file accordingly.
- Audit access to the secrets file and monitor for unauthorized access attempts.
- Ensure backups of the secrets file are also secured and access-controlled.
- Educate team members about the importance of secret management and best practices.
- Review and update your secret management practices periodically to adapt to evolving security threats.
- Consider encrypting the secrets file if it contains highly sensitive information, and implement decryption logic in your application startup process.
- Use environment variables as an alternative to files for passing secrets, especially in containerized environments.
- Document the secret management process clearly for your team to ensure consistent practices.
- Implement logging and alerting for any access to the secrets file to detect potential security breaches.
- Test your secret management implementation regularly to ensure it works as expected and that secrets are not exposed inadvertently.
- Use version control ignore files (e.g., .gitignore) to ensure that secrets files are not accidentally committed to the repository.
- Regularly review and update the secrets file to remove any unused or outdated secrets.
