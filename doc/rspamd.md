Rspamd Integration
==================

Robin can be configured to scan emails for spam, phishing, and other malicious characteristics using Rspamd.

Configuration
-------------

Rspamd integration is configured in the `cfg/rspamd.json5` file.

Here is an example `rspamd.json5` file:

```json5
{
  enabled: false,
  host: "localhost",
  port: 11333,

  // Connection timeout in seconds
  timeout: 30,

  // Enable SPF scanning.
  spfScanEnabled: true,

  // Enable DKIM scanning.
  dkimScanEnabled: true,

  // Enable DMARC scanning.
  dmarcScanEnabled: true,

  // Required spam score threshold for rejection
  requiredScore: 7.0,

  // Action to take when spam/phishing is detected
  // "reject" - Reject the email with a 5xx error
  // "discard" - Accept the email and discard it
  onSpam: "reject"
}
```

### Options

- **enabled**: A boolean to enable or disable Rspamd scanning. Defaults to `false`.
- **host**: The hostname or IP address of the Rspamd daemon. Defaults to `localhost`.
- **port**: The port number of the Rspamd daemon. Defaults to `11333`.
- **timeout**: The connection timeout in seconds. Defaults to `30`.
- **spfScanEnabled**: Enable SPF scanning.
- **dkimScanEnabled**: Enable DKIM scanning.
- **dmarcScanEnabled**: Enable DMARC scanning.
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

### Email Authentication Options

The `RspamdClient` supports configuring email direction and authentication scanning options.

#### Setting Email Direction

```java
RspamdClient client = new RspamdClient();

// Set direction to INBOUND (default)
client.setEmailDirection(EmailDirection.INBOUND);

// Or set to OUTBOUND
client.setEmailDirection(EmailDirection.OUTBOUND);
```

#### Configuring Authentication Scanning

```java
// Enable/disable individual authentication checks
client.setSpfScanEnabled(true);    // Enable SPF checking
client.setDkimScanEnabled(true);   // Enable DKIM checking
client.setDmarcScanEnabled(true);  // Enable DMARC checking
```

### DKIM Signing (TODO)

Robin can be configured to sign outbound emails with DKIM signatures using Rspamd. DKIM signing requires:

1. **Private keys configured in Rspamd** - Pre-generated and stored on the Rspamd server
2. **Public keys published in DNS** - Must be available for recipients to verify
3. **Robin configuration** - Specify which domain and selector to use for signing

#### How DKIM Signing Works

When DKIM signing is enabled, Robin sends the configured domain and selector to Rspamd. Rspamd then:

1. Looks up the private key using the domain/selector combination
2. Generates a DKIM signature over the email headers and body
3. Adds the DKIM-Signature header to the email
4. Returns the signed email

**Important**: The private keys must already exist in Rspamd's keystore before Robin can use them. Robin only tells Rspamd which key to use; it does not provide the key itself.

#### Configuring DKIM Keys in Rspamd

DKIM keys are configured at the Rspamd server level, not in Robin. Here's how to set them up:

**1. Generate DKIM Key Pair (on Rspamd server)**

```bash
# Generate a 2048-bit RSA key pair
openssl genrsa -out example.com.default.key 2048

# Extract the public key
openssl rsa -in example.com.default.key -pubout -out example.com.default.pub
```

**2. Store Private Key in Rspamd**

Private keys are typically stored in `/etc/rspamd/dkim/` or as configured in Rspamd:

```bash
# Copy private key to Rspamd dkim directory
sudo cp example.com.default.key /etc/rspamd/dkim/

# Ensure proper permissions
sudo chmod 600 /etc/rspamd/dkim/example.com.default.key
sudo chown rspamd:rspamd /etc/rspamd/dkim/example.com.default.key
```

**3. Configure Rspamd DKIM Module**

In `/etc/rspamd/local.d/dkim_signing.conf`:

```conf
# Enable DKIM signing
enabled = true;

# Configure signing domains
sign_domains {
  "example.com" {
    # Path to private keys for this domain
    path = "/etc/rspamd/dkim/example.com";
    # Selector to use
    selector = "default";
  };
};

# Sign all outbound mail (optional)
sign_all = true;

# Use RELAXED/RELAXED canonicalization (recommended)
relaxed_sign = true;
```

**4. Publish Public Key in DNS**

Extract the public key value and publish it in DNS:

```bash
# Extract public key in DNS format
openssl rsa -in example.com.default.key -pubout -outform PEM
```

Publish as a TXT record at `default._domainkey.example.com`:

```dns
default._domainkey.example.com. 300 IN TXT "v=DKIM1; k=rsa; p=MIGfMA0GCSq..."
```

#### Using DKIM Signing in Robin

Once DKIM keys are configured in Rspamd, enable DKIM signing in Robin:

```java
RspamdClient client = new RspamdClient();

// Set outbound direction
client.setEmailDirection(EmailDirection.OUTBOUND);

// Enable DKIM signing
client.setDkimSigningOptions(
    "example.com",     // domain (must match Rspamd configuration)
    "default"          // selector (must match Rspamd configuration)
);

// Now when scanning, Rspamd will sign with DKIM
Map<String, Object> result = client.scanBytes(emailData);
```

#### Complete Example

```java
RspamdClient client = new RspamdClient("rspamd.example.com", 11333);

// Configure for outbound scanning and signing
client.setEmailDirection(EmailDirection.OUTBOUND);
client.setDkimSigningOptions(true, "example.com", "default");

// Scan and sign the email
Map<String, Object> result = client.scanBytes(emailData);

if ((Boolean) result.get("spam")) {
    System.out.println("Email was detected as spam");
} else {
    System.out.println("Email passed all checks and was signed");
}
```

#### Troubleshooting DKIM Signing

- **Signing not happening**: Check that the domain and selector match exactly with Rspamd configuration
- **"No private key found"**: Verify the key file exists at the path configured in Rspamd
- **Permission denied**: Check that the Rspamd process has read permission on the private key file
- **DNS verification fails**: Ensure the public key is correctly published in DNS and formatted properly
