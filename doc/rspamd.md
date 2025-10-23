Rspamd Integration
==================

Robin can be configured to scan emails for spam, phishing, and other malicious characteristics using Rspamd.

Configuration
-------------

Rspamd integration is configured in the `cfg/rspamd.json5` file.

Here is an example `rspamd.json5` file:

```json5
{
  rspamd: {
    enabled: false,
    host: "localhost",
    port: 11333,

    // Connection timeout in seconds
    timeout: 30,

    // Required spam score threshold for rejection
    requiredScore: 7.0,

    // Action to take when spam/phishing is detected
    // "reject" - Reject the email with a 5xx error
    // "discard" - Accept the email and discard it
    onSpam: "reject"
  }
}
```

### Options

- **enabled**: A boolean to enable or disable Rspamd scanning. Defaults to `false`.
- **host**: The hostname or IP address of the Rspamd daemon. Defaults to `localhost`.
- **port**: The port number of the Rspamd daemon. Defaults to `11333`.
- **timeout**: The connection timeout in seconds. Defaults to `30`.
- **requiredScore**: The spam score threshold above which an email is considered spam. Defaults to `7.0`.
- **onSpam**: The action to take when spam/phishing is detected.
  - `reject`: Reject the email with a `554 5.7.1 Spam detected` error. This is the default.
  - `discard`: Accept the email but silently discard it.

How It Works
------------

Rspamd is a powerful spam filtering system that uses a variety of detection methods:

- **Bayesian filters**: Statistical analysis of email content
- **Heuristic rules**: Pattern matching based on known spam characteristics
- **SPF/DKIM/DMARC validation**: Email authentication checks
- **URL reputation**: Detection of malicious URLs
- **Phishing detection**: Identification of phishing attempts
- **Malware detection**: Integration with malware scanning engines

When an email is received and stored, Robin performs the following steps:

1. Scans the email with ClamAV (if enabled) for viruses
2. Scans the email with Rspamd (if enabled) for spam and phishing
3. If the email passes both scans, it is accepted and further processed
4. If either scanner detects a threat, the configured action is taken (reject or discard)

Programmatic Usage
------------------

The `RspamdClient` class provides a simple way to interact with the Rspamd daemon.

### Creating a Client

You can create a `RspamdClient` instance with the default constructor, which uses `localhost:11333`.

```java
RspamdClient rspamdClient = new RspamdClient();
```

Or you can specify the host and port.

```java
RspamdClient rspamdClient = new RspamdClient("rspamd.example.com", 11333);
```

### Checking Server Status

#### Pinging the Server

```java
boolean available = rspamdClient.ping();
if (available) {
    System.out.println("Rspamd server is available");
}
```

#### Getting Server Information

```java
var info = rspamdClient.getInfo();
if (info.isPresent()) {
    System.out.println("Rspamd version: " + info.get().get("version"));
}
```

### Scanning

The client can scan files, byte arrays, and input streams.

#### Scanning a File

```java
File file = new File("/path/to/email.eml");
Map<String, Object> result = rspamdClient.scanFile(file);

if ((Boolean) result.get("spam")) {
    System.out.println("Email is spam with score: " + rspamdClient.getScore());
}
```

#### Scanning a Byte Array

```java
byte[] data = ...;
Map<String, Object> result = rspamdClient.scanBytes(data);

boolean isSpam = rspamdClient.isSpam(data);
if (isSpam) {
    System.out.println("Content is spam");
}
```

#### Scanning an Input Stream

```java
InputStream inputStream = ...;
Map<String, Object> result = rspamdClient.scanStream(inputStream);

if ((Boolean) result.get("spam")) {
    System.out.println("Email is spam");
}
```

### Extracting Results

After scanning, you can extract detailed information about the scan.

#### Getting the Spam Score

```java
Map<String, Object> result = rspamdClient.scanBytes(emailData);
double score = rspamdClient.getScore();
System.out.println("Spam score: " + score);
```

#### Getting Detected Symbols

Rspamd assigns symbols (rule names) to emails based on what was detected. These can be used to understand why an email was flagged.

```java
Map<String, Object> symbols = rspamdClient.getSymbols();
for (Map.Entry<String, Object> entry : symbols.entrySet()) {
    System.out.println("Symbol: " + entry.getKey() + " Score: " + entry.getValue());
}
```

Common symbols include:
- `BAYES_SPAM` / `BAYES_HAM`: Bayesian filter results
- `PHISH`: Phishing attempt detected
- `DKIM_SIGNED` / `DKIM_VALID`: DKIM signature results
- `SPF_FAIL` / `SPF_PASS`: SPF check results
- `MISSING_MID`: Missing Message-ID header
- `SUSPICIOUS_RECIPS`: Suspicious recipient list

#### Getting the Complete Scan Result

```java
Map<String, Object> lastResult = rspamdClient.getLastScanResult();
System.out.println("Full scan result: " + lastResult);
```
