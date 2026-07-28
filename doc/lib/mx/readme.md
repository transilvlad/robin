# MX Resolution Library

MX resolution with DANE and MTA-STS security policy support - RFC 7672 & RFC 8461

## Quick Start

```java
import com.mimecast.robin.mx.MXResolver;
import com.mimecast.robin.smtp.security.SecureMxRecord;
import com.mimecast.robin.smtp.security.SecurityPolicy;

// Resolve MX with security policies
MXResolver resolver = new MXResolver();
List<SecureMxRecord> mxList = resolver.resolveSecureMx("example.com");

for (SecureMxRecord mx : mxList) {
    SecurityPolicy policy = mx.getSecurityPolicy();
    System.out.println("MX: " + mx.getHostname());
    System.out.println("Policy: " + policy.getType());
    System.out.println("TLS Required: " + policy.isTlsMandatory());
}
```

## Security Policy Priority

Per RFC 8461 Section 2: **DANE takes precedence over MTA-STS**

1. DANE - If TLSA records exist
2. MTA-STS - If no DANE
3. Opportunistic - If neither

## SMTP Routing Behavior (RFC 5321 / RFC 7505)

Robin routing follows these defaults for all outbound delivery:

1. Try MX records first.
2. If no MX exists, use implicit fallback (domain A/AAAA) per RFC 5321.
3. If the domain publishes Null MX (`MX 0 .`), treat it as non-deliverable and do not fallback (RFC 7505).

These are controlled via `server.json5` DNS options:

```json5
dns: {
  mxImplicitFallbackEnabled: true,
  mxNullMxHardFailEnabled: true,
  mxAddressFamilyPreference: "ipv4_first" // ipv4_first | ipv6_first | system
}
```

## See Full Documentation

- Complete API documentation in source JavaDoc  
- [DANE Library](../dane/readme.md)
- [MTA-STS Library](../mta-sts/readme.md)
- [Usage in Robin](../../security/dane-mta-sts-usage.md)
