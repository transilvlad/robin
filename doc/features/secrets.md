# External configuration secrets

Use an explicit whole-string reference in a JSON5 value:

```json5
{
  password: "{$secret:MTA_DB_PASSWORD}"
}
```

Robin resolves this reference only while loading configuration into memory.
The configuration store seeds, compares, and downloads the **reference**, not
its value. It must not be replaced with a literal credential in Admin.
Ordinary SMTP magic such as `{$transactionUid}` is unchanged.

## Secret sources

Resolution order for `{$secret:NAME}`:

1. Key `NAME` in a UTF-8 Java properties file selected by `-Dsecrets.file=...`.
2. File selected by environment variable `NAME_FILE`.
3. Environment variable `NAME`.

A configured but unreadable file, missing/empty secret, or reference embedded
inside a larger string is an error. A `_FILE` secret has one trailing newline
removed; other characters are preserved. Values are JSON-escaped, so quotes,
backslashes, and Unicode do not corrupt configuration. Rotate files/environment
and restart Robin to apply changes consistently across connection pools.

Properties file example (keep outside version control, mode `0600`):

```properties
MTA_DB_PASSWORD=replace-with-a-unique-secret
MAILBOX_DB_PASSWORD=replace-with-another-secret
```

Java properties escaping rules apply, including backslash escaping.

```sh
java -Dsecrets.file=/secure/robin.properties \
  -jar target/robin.jar --server --conf cfg/
```

For containers, mount a secret file read-only and set
`MTA_DB_PASSWORD_FILE=/run/secrets/mta_db_password`.
Do not pass credential values as JVM arguments: arguments can be visible to
other processes and Robin's session magic includes JVM property names.

## Ownership and operational limits

`config.json5` is a local bootstrap file, excluded from database synchronization.
For Admin-managed deployments, the PostgreSQL store owns the remaining JSON5
configuration. Enable `required: true` alongside `enabled: true` in the bootstrap
file to fail a synchronization pass rather than silently use stale configuration
after a store error. The backward-compatible default is `required: false`.

External secret resolution does not scrub previously stored literal passwords.
Replace old literals in base and per-server override rows with references, and
rotate those passwords. Protect database backups, secret-file backups, the Admin
account, and filesystem access. Anyone authorized to change configuration can
change where credentials are used; references are not a substitute for access
control. Existing APIs may expose effective configuration; secure management
endpoints before exposing the deployment.

See [Robin Full Suite](../developer/robin-suite.md) for separate database roles
and suite credential provisioning. TLS/network hardening remains required.
