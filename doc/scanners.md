# Scan Results Feature

## Overview

The `MessageEnvelope` class now supports aggregating scan results from multiple security scanners (Rspamd and ClamAV) in a thread-safe manner.

## Features

- **Thread-safe storage**: Uses `Collections.synchronizedList()` for concurrent access
- **Multiple scanner support**: Aggregates results from Rspamd, ClamAV, and potentially other scanners
- **Proper cloning**: One-level deep copy of scan results when cloning envelopes (nested Maps and Collections are copied, but not their contents)
- **Null-safe**: Automatically filters out null or empty scan results

## Usage

### Accessing Scan Results

```java
List<MessageEnvelope> envelopes = connection.getSession().getEnvelopes();
if (!envelopes.isEmpty()) {
    MessageEnvelope envelope = envelopes.getLast();
    List<Map<String, Object>> scanResults = envelope.getScanResults();

    // Check if any scanner detected malware
    boolean hasMalware = scanResults.stream()
        .anyMatch(r -> Boolean.TRUE.equals(r.get("infected")));

    // Get Rspamd spam score
    Double spamScore = scanResults.stream()
        .filter(r -> "rspamd".equals(r.get("scanner")))
        .findFirst()
        .map(r -> (Double) r.get("score"))
        .orElse(0.0);
}
```

### Scan Result Format

#### Rspamd Scan Result
```json
{
  "scanner": "rspamd",
  "score": 2.5,
  "spam": false,
  "symbols": {
    "R_SPF_ALLOW": 0.0,
    "R_DKIM_ALLOW": 0.0
  }
}
```

#### ClamAV Scan Result (Clean)
```json
{
  "scanner": "clamav",
  "infected": false,
  "part": "RAW"
}
```

#### ClamAV Scan Result (Infected)
```json
{
  "scanner": "clamav",
  "infected": true,
  "viruses": {
    "attachment.pdf": ["EICAR-Test-File"]
  },
  "part": "application/pdf"
}
```

## Implementation Details

### SpamStorageProcessor

The `SpamStorageProcessor` now saves Rspamd scan results to the envelope:

```java
Map<String, Object> scanResult = rspamdClient.scanFile(emailFile);
if (!scanResult.isEmpty()) {
    Map<String, Object> rspamdResult = new HashMap<>(scanResult);
    rspamdResult.put("scanner", "rspamd");
    connection.getSession().getEnvelopes().getLast().addScanResult(rspamdResult);
}
```

### AVStorageProcessor

The `AVStorageProcessor` saves ClamAV virus detection results:

```java
if (clamAVClient.isInfected(file)) {
    Map<String, Collection<String>> viruses = clamAVClient.getViruses();
    if (viruses != null && !viruses.isEmpty()) {
        Map<String, Object> clamavResult = new HashMap<>();
        clamavResult.put("scanner", "clamav");
        clamavResult.put("infected", true);
        clamavResult.put("viruses", viruses);
        clamavResult.put("part", partInfo);
        connection.getSession().getEnvelopes().getLast().addScanResult(clamavResult);
    }
}
```

## Thread Safety

The scan results list is thread-safe and can be accessed from multiple threads:

```java
// Multiple threads can safely add scan results
envelope.addScanResult(rspamdResult);  // Thread 1
envelope.addScanResult(clamavResult);  // Thread 2
```

## Testing

Comprehensive tests are available:
- `SpamStorageProcessorTest` - Tests for Rspamd integration
- `AVStorageProcessorTest` - Tests for ClamAV integration
- `MessageEnvelopeScanResultsIntegrationTest` - Integration tests

Run tests:
```bash
mvn test -Dtest=*ScanResults*
```

## Example: Complete Email Processing

```java
MessageEnvelope envelope = new MessageEnvelope()
    .setMail("sender@example.com")
    .addRcpt("recipient@example.com");

// ... email is processed through storage processors ...

// After processing, check scan results
List<Map<String, Object>> scanResults = envelope.getScanResults();

// Verify email is safe
boolean isSafe = scanResults.stream()
    .filter(r -> "clamav".equals(r.get("scanner")))
    .noneMatch(r -> Boolean.TRUE.equals(r.get("infected")));

Double spamScore = scanResults.stream()
    .filter(r -> "rspamd".equals(r.get("scanner")))
    .findFirst()
    .map(r -> (Double) r.get("score"))
    .orElse(0.0);

if (isSafe && spamScore < 7.0) {
    // Safe to deliver
    System.out.println("Email passed all security checks");
}
```
