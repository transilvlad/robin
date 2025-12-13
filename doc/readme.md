# Robin MTA Documentation

Welcome to the Robin MTA documentation. This guide will help you navigate the documentation based on your role and needs.

## Full Suite

Complete email infrastructure bundling Robin with Dovecot, PostgreSQL, ClamAV, Rspamd, and Roundcube:

- **[Robin Full Suite](developer/robin-suite.md)** - Architecture, deployment, and configuration
- **[Full Suite Testing](testing/full-suite.md)** - Integration testing and validation

## Quick Navigation

### 🚀 Getting Started

New to Robin? Start here:
- [Getting Started Guide](user/getting-started.md) - Installation and quick start
- [CLI Reference](user/cli.md) - Command-line interface
- [Server configuration](user/server.md) - Running as SMTP server
- [Client usage](user/client.md) - Running as test client

### 👤 End Users

**Server Administrators**:
- [Server Configuration](user/server.md)
- [Bot Configuration](features/bots.md)
- [Bot Examples](features/bot-examples.md)

**Test Engineers**:
- [Client Usage](user/client.md)
- [SMTP Test Cases](testing/case-smtp.md)
- [HTTP Assertions](testing/case-http.md)

### ⚙️ Features

- [Message Queue & Relay](features/queue.md)
- [SMTP Proxy](features/proxy.md)
- [Webhooks](features/webhooks.md)
- [Virus Scanning (ClamAV)](features/clamav.md)
- [Spam Scoring (Rspamd)](features/rspamd.md)
- [Scanner Results](features/scanners.md)
- [HTTP API Endpoints](features/endpoints.md)
- [Prometheus Metrics](features/prometheus.md)
- [Secret Management](features/secrets.md)
- [HashiCorp Vault](features/vault.md)
- [Magic Variables](features/magic.md)

### 🔒 Security

- [DANE & MTA-STS in Robin](security/dane-mta-sts-usage.md) - How Robin enforces security policies
- [TLS Configuration](user/server.md#tls) - Protocols and ciphers
- [SMTP Authentication](user/server.md#authentication) - Auth configuration

### 🧪 Testing

- **[Full Suite Testing](testing/full-suite.md)** - Full suite integration testing
- [SMTP Test Cases](testing/case-smtp.md) - Writing SMTP tests
- [HTTP Assertions](testing/case-http.md) - External validation
- [MIME Building](testing/mime.md) - Dynamic MIME construction in test cases

### 📚 Reusable Libraries

Robin includes several standalone, reusable libraries:

**Email & DNS Libraries**:
- [DANE Library](lib/dane/readme.md) - TLSA record checking
- [MX Resolution Library](lib/mx/readme.md) - MX resolution with security policies
- [MTA-STS Library](lib/mta-sts/readme.md) - MTA-STS policy handling
- [MIME Library](lib/mime.md) - Email parsing and building
- [IMAP Library](lib/imap.md) - IMAP helper utilities

**Infrastructure Libraries**:
- [Dovecot SASL](lib/dovecot-sasl.md) - Dovecot authentication
- [Received Header Parser](lib/received-header.md) - Header analysis
- [Header Wrangler](lib/header-wrangler.md) - Header manipulation
- [HTTP Request Library](lib/request.md) - HTTP client

### 👨‍💻 Developers

**Architecture & Deployment**:
- **[Robin Full Suite](developer/robin-suite.md)** - Complete infrastructure architecture
- [SMTP Flow Diagrams](developer/flowchart.md) - Visual flow charts

**Extending Robin**:
- [Plugin Development](developer/plugin-development.md) - Creating plugins

## Documentation Organization

### By Audience

```
doc/
├── user/           # End users (server admins, test engineers)
├── features/       # Feature-specific configuration
├── testing/        # Test case authors
├── security/       # Security administrators
├── lib/            # Library consumers (programmatic usage)
├── developer/      # Contributors and extenders
└── reference/      # API and configuration reference
```

### By Usage Type

**As-Is Usage** (Run Robin directly):
- user/
- features/
- testing/
- security/

**Programmatic Usage** (Use Robin's components):
- lib/
- developer/
- reference/

## Common Tasks

### Running Robin

**As SMTP Server**:
```bash
java -jar robin.jar --server --path cfg/
```
→ See [Server Documentation](user/server.md)

**As Test Client**:
```bash
java -jar robin.jar --client --case test.json5
```
→ See [Client Documentation](user/client.md)

**Check DANE**:
```bash
java -jar robin.jar --dane --domain example.com
```
→ See [DANE Library](lib/dane/readme.md)

**Check MTA-STS**:
```bash
java -jar robin.jar --mtasts --domain example.com
```
→ See [MTA-STS Library](lib/mta-sts/readme.md)

### Using as Library

**DANE Checking**:
```java
List<DaneRecord> records = DaneChecker.checkDane("mail.example.com");
```
→ See [DANE Library](lib/dane/readme.md)

**MX Resolution with Security**:
```java
List<SecureMxRecord> mxList = new MXResolver().resolveSecureMx("example.com");
```
→ See [MX Library](lib/mx/readme.md)

**MIME Parsing**:
```java
EmailParser parser = new EmailParser(inputStream);
```
→ See [MIME Library](lib/mime.md)

## Support

- **Issues**: Report bugs and feature requests on GitHub
- **Configuration Help**: See configuration files in `cfg/` directory
- **API Questions**: Check JavaDoc or package-info files

## Contributing

Want to contribute? See [Contributing Guide](../contributing.md)
