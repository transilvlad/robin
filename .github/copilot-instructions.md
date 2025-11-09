# GitHub Copilot Instructions for Robin MTA Server and Tester

## Project Overview

Robin MTA Server and Tester is a development, debug, and testing tool for MTA (Mail Transfer Agent) architects. It serves both as:
1. A lightweight MTA server with Dovecot SASL AUTH and mailbox integration
2. A comprehensive testing framework for SMTP/ESMTP/LMTP functionality

The project is built with single responsibility principle in mind, providing reusable libraries and stand-alone tools.

## Technology Stack

- **Language**: Java 21 (required)
- **Build Tool**: Maven 3.x
- **Key Frameworks**:
  - Jakarta Mail for email handling
  - JUnit 5 (Jupiter) for testing
  - Micrometer for metrics (Prometheus, Graphite)
  - OkHttp for HTTP client operations
  - Log4j 2 for logging

## Development Environment Setup

### Prerequisites

- **Java Development Kit (JDK) 21** - This is a strict requirement
  - The project will NOT compile with Java 17 or earlier versions
  - Set `JAVA_HOME` to point to JDK 21
- **Apache Maven 3.9+**
- **Git**

### Verifying Java Version

```bash
java -version  # Should show version 21.x.x
```

If using a different version, set JAVA_HOME:
```bash
export JAVA_HOME=/path/to/jdk-21
export PATH=$JAVA_HOME/bin:$PATH
```

## Build Commands

### Clean Build
```bash
mvn clean compile
```

### Run Tests
```bash
mvn test
```

### Package JAR
```bash
mvn package
```

This creates:
- Standard JAR: `target/robin.jar`
- Fat JAR with dependencies: `target/robin-jar-with-dependencies.jar`

### Generate JavaDoc
```bash
mvn javadoc:javadoc
```

JavaDoc output: `target/reports/apidocs/`

## Project Structure

```
robin/
├── src/
│   ├── main/
│   │   ├── java/com/mimecast/robin/
│   │   │   ├── Main.java                 # Entry point
│   │   │   ├── smtp/                     # SMTP client and server
│   │   │   ├── config/                   # Configuration handling
│   │   │   ├── storage/                  # Storage implementations
│   │   │   ├── mx/                       # MTA-STS library
│   │   │   ├── mime/                     # MIME parsing and building
│   │   │   ├── util/                     # Utilities
│   │   │   └── ...
│   │   └── resources/
│   └── test/
│       └── java/com/mimecast/robin/
├── doc/                                   # Comprehensive documentation
├── cfg/                                   # Sample configurations
├── pom.xml                                # Maven configuration
└── .github/
    └── workflows/
        └── maven.yml                      # CI/CD pipeline
```

## Code Style and Conventions

### General Guidelines

1. **Follow Existing Style**: Match the coding style and naming conventions of existing code
2. **Code Quality**: Run comprehensive code quality analysis before submitting (IntelliJ IDEA inspections or SonarQube)
3. **Warning Suppression**: If suppressing a code quality rule, be as specific as possible to avoid suppressing other applicable rules
4. **Documentation**: Code is extensively documented with JavaDoc - maintain this standard
5. **Single Responsibility**: Follow the single responsibility principle used throughout the project

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

## Testing

### Test Framework

- **JUnit 5 (Jupiter)** is used for all tests
- Tests are located in `src/test/java/` mirroring the main source structure

### Running Tests

```bash
# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=ClassName

# Run tests with verbose output
mvn test -X
```

### Test Conventions

1. Test classes end with `Test` suffix (e.g., `EmailParserTest`)
2. Test methods should be descriptive and follow pattern: `testMethodName_scenario_expectedResult`
3. Use JUnit 5 annotations: `@Test`, `@BeforeEach`, `@AfterEach`, etc.
4. Parameterized tests use `@ParameterizedTest` with `@ValueSource`, `@CsvSource`, etc.
5. Mock external services using appropriate mocking frameworks (MockWebServer for HTTP)

### Test Coverage

- Aim for comprehensive test coverage
- Test both success and failure scenarios
- Include edge cases and boundary conditions
- Current test suite: 600+ tests

## Special Considerations

### Sample Passwords

The project uses sample passwords for testing and demonstration:
- `notMyPassword` - Demo password (doesn't meet complexity requirements)
- `1234` - Unit test sample
- `giveHerTheRing` - Unit test and documentation sample
- `avengers` - Test keystore password

**These are NOT production passwords** and should never be changed to real credentials.

### Docker Support

The project can be containerized:
- `Dockerfile` - For standalone Robin container
- `docker-compose.yaml` - For running Robin
- `docker-compose.dovecot.yaml` - For integration with Dovecot

### Configuration Files

Test cases and configurations use JSON files:
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

## Common Tasks

### Adding a New Feature

1. Review existing code in related packages
2. Follow single responsibility principle
3. Add appropriate unit tests
4. Update relevant documentation in `doc/` if needed
5. Ensure code quality passes inspection
6. Submit PR with clear description

### Fixing a Bug

1. Write a failing test that reproduces the bug
2. Fix the bug with minimal changes
3. Verify the test now passes
4. Ensure no regressions in other tests
5. Document the fix in PR description

### Adding Dependencies

1. Add dependency to `pom.xml`
2. Specify exact version (avoid version ranges)
3. Justify the addition in PR description
4. Ensure license compatibility

## Continuous Integration

### GitHub Actions Workflow

- **Trigger**: Push to `master` or PR to `master`
- **Java Version**: JDK 21 (Temurin distribution)
- **Build**: `mvn -B package --file pom.xml`
- **JavaDoc**: Generated and deployed to GitHub Pages on master branch
- **Maven Cache**: Enabled for faster builds

### Build Requirements

- All tests must pass
- Code must compile without warnings
- JavaDoc must generate without errors

## Documentation

Extensive documentation is available in the `doc/` directory:

- `introduction.md` - Getting started guide
- `cli.md` - Command-line interface usage
- `client.md` - SMTP/ESMTP/LMTP client usage
- `server.md` - Server configuration
- `case-smtp.md` - Test case definitions
- Library-specific docs in `doc/lib/`

Always update relevant documentation when making functional changes.

## Getting Help

- **Issues**: https://github.com/transilvlad/robin/issues
- **Maintainer**: Vlad Marian <transilvlad@gmail.com>
- **Documentation**: Comprehensive docs in `doc/` directory

## Summary for Quick Reference

```bash
# Build
mvn clean compile

# Test
mvn test

# Package
mvn package

# JavaDoc
mvn javadoc:javadoc
```

**Critical**: Always use Java 21. The project will not build with earlier versions.
