# DANE Library

DNS-Based Authentication of Named Entities (DANE) for SMTP - RFC 7672

## Quick Start

```java
import com.mimecast.robin.mx.dane.DaneChecker;
import com.mimecast.robin.mx.dane.DaneRecord;

// Check DANE for MX host
List<DaneRecord> records = DaneChecker.checkDane("mail.example.com");

if (!records.isEmpty()) {
    System.out.println("DANE enabled - TLS MANDATORY");
    for (DaneRecord record : records) {
        System.out.println(record.getUsageDescription());
    }
}
```

## CLI Tool

```bash
java -jar robin.jar --dane --domain example.com
java -jar robin.jar --dane --mx mail.example.com
```

## See Full Documentation

- Complete API documentation in source JavaDoc
- [MX Resolution with Security Policies](../mx/readme.md)
- [Usage in Robin](../../security/dane-mta-sts-usage.md)
