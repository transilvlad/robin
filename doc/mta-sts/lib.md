Library Usage
=============

Basic implementation example using default Java Trustmanager and the provided MemoryCache.

It would be good to implement a cloud cache solution if deploying this in a cloud environment.

Additionally, you may wish to implement your own TrustManager implementation if you use one.

Overview
--------
The MTA-STS library components help you resolve and filter MX records under MTA-STS policy constraints and then group domains that share identical MX routes.

Classes documented here:
- `StrictMx`: Fetches raw MX records and filters them through an MTA-STS policy (if present and valid) for a single domain.
- `MXResolver`: High-level helper that prefers MTA-STS filtered ("Strict") MX records and falls back to regular DNS MX records. It also groups domains into route objects.
- `MXRoute`: Represents a unique ordered MX server list (a "route") shared by one or more domains, with hashing for identity/deduplication.

When to use which:
- Use `StrictMx` directly if you only need the strictly allowed MX hosts under an MTA-STS policy and want to handle fallback yourself.
- Use `MXResolver.resolveMx(domain)` for a single domain with automatic fallback from strict to standard MX.
- Use `MXResolver.resolveRoutes(domains)` to batch domains and cluster them by identical MX topology.

Quick Start
-----------
Add the dependency (from this project build). From `pom.xml`, ensure the module is on your classpath, then:

```java
MXResolver resolver = new MXResolver();
List<DnsRecord> records = resolver.resolveMx("example.com");
for (DnsRecord r : records) {
    System.out.println(r.getPriority() + " " + r.getValue());
}
```

If MTA-STS strict hosts are found they are returned; otherwise normal MX records are returned. If neither are available the list will be empty.

StrictMx Usage
--------------
`StrictMx` focuses only on strict (policy-authorized) MX records. If a valid policy exists but yields no matches, it returns an empty list (it does NOT fall back to regular MX). If no policy exists, it also returns an empty list.

```java
StrictMx strictMx = new StrictMx("example.com");
List<DnsRecord> stsOnly = strictMx.getMxRecords();
if (stsOnly.isEmpty()) {
    // Decide whether to attempt a non-strict fallback.
}
```

Key points:
- Internally, it lazily initializes a shared `StrictTransportSecurity` instance (expensive due to TrustManager setup).
- `getPolicy()` tries to load and validate the domain's MTA-STS policy and exposes reporting info if available.
- `getMxRecords()` filters the raw MX set using `policy.matchMx(...)` and returns ONLY those that match when a valid policy exists.

You can inspect the policy:
```java
StsPolicy policy = strictMx.getPolicy();
if (policy != null) {
    System.out.println("Policy mode: " + policy.getMode());
}
```

MXResolver Usage
----------------
`MXResolver` abstracts two steps:
1. Attempt strict MX via `StrictMx`.
2. Fallback to standard DNS MX via `XBillDnsRecordClient` if strict returns none.

Single domain:
```java
MXResolver resolver = new MXResolver();
List<DnsRecord> ordered = resolver.resolveMx("example.com");
ordered.forEach(r -> System.out.printf("%d %s%n", r.getPriority(), r.getValue()));
```

Batch domains and group into routes:
```java
List<String> domains = List.of("example.com", "example.net", "other.org");
List<MXRoute> routes = resolver.resolveRoutes(domains);
for (MXRoute route : routes) {
    System.out.println("Route hash: " + route.getHash());
    System.out.println("Domains: " + route.getDomains());
    for (MXServer server : route.getServers()) {
        System.out.printf("  %d %s%n", server.getPriority(), server.getName());
    }
}
```

Implementation details:
- After resolution, MX records are deterministically ordered (priority asc, then hostname asc lowercased) before hashing.
- A SHA-256 hash of the canonical ordered list identifies a unique route.
- Domains sharing identical ordered MX sets are merged into the same `MXRoute` instance.

MXRoute Usage
-------------
`MXRoute` represents a deduplicated path defined by an ordered list of `MXServer` objects and the set of domains using it.

Essential methods:
- `getHash()`: Unique SHA-256 fingerprint of route order.
- `getServers()`: Ordered MX server objects.
- `getDomains()`: Domains linked to this route.
- `getIpAddresses()`: Flattened list of all IPs discovered per MX server (useful for firewall whitelists or monitoring).

Example: Produce a target list of IPs for connection warm-up or monitoring.
```java
for (MXRoute route : routes) {
    List<String> ips = route.getIpAddresses();
    // Send synthetic probes or perform health checks.
}
```

Combining Everything
--------------------
A typical flow for sending: resolve routes once, then iterate domains grouped by route.

```java
MXResolver resolver = new MXResolver();
List<MXRoute> routes = resolver.resolveRoutes(recipientDomains); // potentially large list
for (MXRoute route : routes) {
    // Attempt connections in order of MX priority for this route
    for (MXServer server : route.getServers()) {
        boolean connected = attemptSmtp(server.getName(), server.getPriority());
        if (connected) {
            // Send messages for all domains sharing this route.
            for (String domain : route.getDomains()) {
                sendMessagesForDomain(domain, server.getName());
            }
            break; // Proceed to next route
        }
    }
}
```

Error Handling & Logging
------------------------
- `StrictMx` logs warnings for policy fetch/validation issues and debug lines for absence of records.
- `MXResolver` logs warnings when no strict MX exists and again when no regular MX exists.
- Always treat an empty MX list as a terminal condition for that domain (queue or bounce based on policy).

Best Practices
--------------
1. Caching: Introduce a distributed cache (e.g., Redis, Memcached) for policy and MX record reuse at scale.
2. Policy Refresh: Respect `max_age` from MTA-STS policy; schedule refresh before expiry.
3. Negative Caching: Cache misses (no policy, no MX) briefly to avoid repeated DNS lookups under high traffic.
4. Concurrency: Share a single `MXResolver` (and thus `StrictTransportSecurity` via `StrictMx`) across threads to minimize TLS TrustManager reinitialization cost.
5. Monitoring: Alert if strict policies vanish or if fallback occurs unusually often—it might indicate policy misconfiguration.

Customization
-------------
TrustManager:
- Provide a custom TrustManager inside the underlying `StrictTransportSecurity` initialization if you need certificate pinning or specific validation behavior.

Cache Implementation:
- Replace the default `MemoryCache` with a distributed cache to share policy state across application instances.
- Store: Policy JSON, fetched timestamp, expiry time, resolved strict MX hostnames.

Integrating Custom DNS Client:
- `MXResolver` currently uses `XBillDnsRecordClient`. You can adapt the code to inject your own DNS resolver that supports DNSSEC or specialized upstream resolvers.

Testing Strategies
------------------
- Unit test canonicalization: Given different host case orderings ensure the produced hash is stable.
- Policy filtering: Mock a `StsPolicy` that matches only a subset of MX records and assert `StrictMx.getMxRecords()` returns the subset.
- Route grouping: Provide multiple domain sets with intentionally identical MX lists (different casing or order) and assert they collapse to single `MXRoute`.

Example JUnit Snippet:
```java
@Test
void groupsDomainsWithIdenticalMx() {
    MXResolver resolver = new MXResolver();
    List<String> domains = List.of("a.example", "b.example");
    List<MXRoute> routes = resolver.resolveRoutes(domains);
    assertEquals(1, routes.size());
    assertEquals(Set.of("a.example", "b.example"), new HashSet<>(routes.get(0).getDomains()));
}
```

Troubleshooting
---------------
Symptom: Empty list from `StrictMx.getMxRecords()`.
- Cause: No valid MTA-STS policy; MX do not match "mx" section; policy fetch error.
- Action: Log policy retrieval debug, fallback using `MXResolver.resolveMx(domain)`.

Symptom: `resolveRoutes` returns many single-domain routes.
- Cause: Domains have distinct MX sets (expected) or ordering differences due to inconsistent priority data.
- Action: Verify DNS priorities; ensure all lookups are consistent; check for transient DNS changes.

Symptom: Performance lag on first strict lookup.
- Cause: TrustManager initialization. This is one-time; consider pre-warming at startup with a known domain.

Security Considerations
-----------------------
- Validate certificates and OCSP revocation (already enabled by system properties in `StrictMx.init()`).
- Consider enabling DNSSEC validation in a custom DNS client.
- Avoid disabling hostname verification in a custom TrustManager.

Production Checklist
--------------------
- [ ] Distributed cache for policies.
- [ ] Health checks for MX servers in each route.
- [ ] Metrics: count of strict vs fallback resolutions.
- [ ] Alerting on policy fetch errors frequency.
- [ ] Periodic rehash verification (to detect unexpected MX drift).

Glossary
--------
- MTA-STS: Mechanism to enforce secure TLS for SMTP between MTAs.
- Strict MX: Policy-authorized MX records only.
- Route: Unique ordered MX server sequence used by one or more domains.

Additional Notes
----------------
The code returns an empty list when strict filtering yields no matches instead of returning the unfiltered MX list. This explicit behavior lets you decide whether to trust fallback or enforce policy strictly.

See also: `doc/mta-sts/mta-sts.md` for protocol fundamentals and policy structure.
