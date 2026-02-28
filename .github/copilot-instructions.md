# Instructions for Robin MTA Server and Tester

## Overview

This is an MTA and MTA Tester, it serves both as:
1. An MTA server with Dovecot SASL AUTH and mailbox integration, ClamAV, Rspamd and Postgres.
2. A comprehensive testing framework for SMTP/ESMTP/LMTP functionality.

The project is built with SOLID principles in mind, providing reusable libraries and stand-alone tools.

## Technology Stack

- **Language**: Java 21 (required)
- **Build Tool**: Maven 3.x
- **Key Frameworks**:
  - JUnit 5 (Jupiter) for testing
  - Micrometer for metrics (Prometheus, Graphite)
  - OkHttp for HTTP client operations
  - Log4j 2 for logging

## Development Environment Setup

### Prerequisites

- **Java Development Kit (JDK) 21** - This is a strict requirement.
  - The project will NOT compile with Java 17 or earlier versions.
- **Apache Maven 3.9+**

## Build

- Standard JAR: `target/robin.jar`
- Fat JAR with dependencies: `target/robin-jar-with-dependencies.jar`

## Code Style and Conventions

### General Guidelines

1. **Follow Existing Style**: Match the coding style and naming conventions of existing code.
2. **Code Quality**: Run comprehensive code quality analysis before submitting (IntelliJ IDEA inspections or SonarQube).
3. **Warning Suppression**: If suppressing a code quality rule, be as specific as possible to avoid suppressing other applicable rules.
4. **Documentation**: Code is extensively documented with JavaDoc and package-info.java - maintain this standard.
5. **Single Responsibility**: Follow the single responsibility principle used throughout the project.

### Naming Conventions

- Classes: `PascalCase` (e.g., `EmailBuilder`, `SmtpClient`)
- Methods: `camelCase` (e.g., `sendMessage`, `parseHeaders`)
- Constants: `UPPER_SNAKE_CASE` (e.g., `DEFAULT_PORT`, `MAX_RETRIES`)
- Package names: lowercase (e.g., `com.mimecast.robin.smtp`)

### Code Organization

- Keep related functionality together in packages
- Prefer composition over inheritance
- Make classes and methods as focused and reusable as possible
- Libraries should be self-contained and reusable

### Test Conventions

1. Test classes end with `Test` suffix (e.g., `EmailParserTest`)
2. Test methods should be descriptive and follow pattern: `testMethodName_scenario_expectedResult`
3. Use JUnit 5 annotations: `@Test`, `@BeforeEach`, `@AfterEach`, etc.
4. Parameterized tests use `@ParameterizedTest` with `@ValueSource`, `@CsvSource`, etc.
5. Mock external services using Mock classes overriding relevant methods.

### Test Coverage

- Aim for comprehensive test coverage
- Test both success and failure scenarios
- Include edge cases and boundary conditions

## Special Considerations

### Sample Passwords

The project uses sample passwords for testing and demonstration:
- `notMyPassword` - Demo password (doesn't meet complexity requirements)
- `1234` - Unit test sample
- `stark/potts/hogan` - Unit test and documentation sample
- `avengers` - Test keystore password

**These are NOT production passwords** and should never be changed to real credentials.

### Docker Support

The project can be containerized:
- `Dockerfile` - For standalone Robin container
- `docker-compose.yaml` - For running Robin
- `.suite/docker-compose.yaml` - For integration with Dovecot, ClamAV, Rspamd and Postgres
- `.perf/robin-dovecot/docker-compose.robin.yaml` - For performance testing with Robin MTA and Dovecot SASL AUTH
- `.perf/robin-dovecot/docker-compose.postfix.yaml` - For performance testing with Postfix MTA and Dovecot SASL AUTH
- `.perf/robin-dovecot-lda/docker-compose.postfix.yaml` - For performance testing with Postfix MTA, Dovecot SASL AUTH and Dovecot LDA
- `.perf/robin-dovecot-lda/docker-compose.robin.yaml` - For performance testing with Robin MTA, Dovecot SASL AUTH and Dovecot LDA
- `.perf/robin-stalwart/docker-compose.postfix.yaml` - For performance testing with Postfix MTA and Stalwart SASL AUTH
- `.perf/robin-stalwart/docker-compose.robin.yaml` - For performance testing with Robin MTA and Stalwart SASL AUTH
- `.perf/stalwart-bare/docker-compose.yaml` - For performance testing with Stalwart stand alone

### Configuration Files

Test cases and configurations use JSON5 files:
- SMTP test cases: Define client behavior for testing
- Server configuration: Define server behavior and scenarios
- See `doc/` directory for comprehensive configuration documentation

## Integration Points

### External Services

- **Dovecot**: For SASL authentication and mailbox integration
- **HashiCorp Vault**: For secrets management
- **ClamAV**: For virus scanning integration
- **Rspamd**: For spam/phishing detection
- **Prometheus**: For metrics collection via remote write
- **Graphite**: Alternative metrics backend

### Webhooks

The server can trigger HTTP webhooks on various SMTP events.

## Documentation

Extensive documentation is available in the `doc/` directory:

- `introduction.md` - Getting started guide
- `cli.md` - Command-line interface usage
- `client.md` - SMTP/ESMTP/LMTP client usage
- `server.md` - Server configuration
- `case-smtp.md` - Test case definitions
- Library-specific docs in `doc/lib/`

Always update relevant documentation when making functional changes.


## Other guidelines
- Always use punctuation and proper grammar in your comments and documentation.
- Always update relevant documentation when making changes to the codebase.
- Do not remove random code comments unless they are irrelevant or incorrect.
- Do not add comments that state the obvious or do not provide additional context.
- Do not add comments in the code about what you changed and why unless explicitly requested.
- When generating documentation, ensure it is clear, concise, and relevant to the code it describes.
