# Email Infrastructure Analysis Bots

## Overview

Robin supports automated email infrastructure analysis bots that can receive emails and automatically reply with diagnostic information about the email and sending infrastructure. This feature is useful for:

- Analyzing email headers and authentication
- Checking DNS records (SPF, DKIM, DMARC)
- Reviewing TLS/SMTP connection details
- Identifying missing or misconfigured infrastructure elements
- Detecting spam and virus scanning results

## How It Works

When an email is sent to a configured bot address:

1. **Detection**: The `ServerRcpt` processor checks if the recipient matches any bot address patterns
2. **Recording**: Matched bot addresses are recorded in the `MessageEnvelope` with their associated bot names
3. **Processing**: After email storage, each matched bot is processed in a separate thread to avoid blocking the SMTP connection
4. **Analysis**: The bot analyzes the email, session data, and infrastructure
5. **Response**: The bot generates a response email with analysis results and queues it for delivery

## Configuration

### Bot Configuration File

Create a `bots.json5` file in your configuration directory:

```json5
{
  // List of bot definitions
  bots: [
    {
      // Regex pattern to match bot addresses
      // Supports sieve addressing: robot+token@example.com
      addressPattern: "^robotSession(\\+[^@]+)?@example\\.com$",

      // List of IP addresses or CIDR blocks allowed to trigger this bot
      // This prevents abuse by restricting who can use the bot
      // Empty list means all IPs are allowed (not recommended for production)
      allowedIps: [
        "127.0.0.1",
        "::1",
        "192.168.1.0/24",
        "10.0.0.0/8"
      ],

      // List of allowed tokens for authentication (alternative to IP restrictions)
      // Tokens are extracted from addresses like: robot+token@example.com
      // Empty list means no token validation (not recommended for production)
      allowedTokens: [
        "secret123",
        "token456"
      ],

      // Name of the bot implementation to use from the factory
      // Currently supported: "session", "email"
      botName: "session"
    }
  ]
}
```

### Configuration Options

#### Address Pattern

The `addressPattern` field uses Java regular expressions to match recipient addresses. Common patterns:

- **Simple address**: `^robot@example\\.com$`
- **With optional token**: `^robot(\\+[^@]+)?@example\\.com$`
- **Multiple prefixes**: `^(robot|analysis|diagnostic)@example\\.com$`

#### Authorization

Bots use **IP-based** or **token-based** authorization to prevent abuse. Authorization succeeds if **either** condition is met:

##### IP Address Restrictions

The `allowedIps` array restricts which source IPs can trigger the bot:

- **Empty list**: IP restriction is disabled
- **Individual IPs**: `["127.0.0.1", "::1"]`
- **CIDR notation**: `["192.168.1.0/24", "10.0.0.0/8"]`

**Note**: The CIDR implementation uses simple prefix matching by extracting the portion before the "/" and checking if the IP starts with that prefix. For example, `192.168.1/24` matches any IP starting with `192.168.1`. This is sufficient for most internal use cases but does not perform proper subnet mask calculations. For production environments requiring strict CIDR validation, consider using a dedicated IP address library.

##### Token Authentication

The `allowedTokens` array provides an alternative authorization method using tokens embedded in bot addresses:

- **Empty list**: Token validation is disabled
- **Token list**: `["secret123", "token456", "mytoken"]`
- **Token extraction**: Tokens are extracted from addresses like `robot+token@example.com`
- **Format**: The token is the text between the first `+` and either the next `+` or the `@` symbol

**Example**:
```json5
{
  addressPattern: "^robot(\\+[^@]+)?@example\\.com$",
  allowedIps: [],  // No IP restriction
  allowedTokens: ["secret123", "token456"],
  botName: "session"
}
```

With this configuration:
- `robot+secret123@example.com` → **Authorized** (valid token)
- `robot+token456@example.com` → **Authorized** (valid token)
- `robot+wrongtoken@example.com` → **Not authorized** (invalid token)
- `robot@example.com` → **Not authorized** (no token provided)

##### Combined Authorization

When **both** `allowedIps` and `allowedTokens` are configured, requests are authorized if **either** the IP matches **or** the token is valid:

```json5
{
  addressPattern: "^robot(\\+[^@]+)?@example\\.com$",
  allowedIps: ["192.168.1.0/24"],
  allowedTokens: ["secret123"],
  botName: "session"
}
```

With this configuration:
- Request from `192.168.1.100` with any address → **Authorized** (IP match)
- Request from `10.0.0.1` with `robot+secret123@example.com` → **Authorized** (token match)
- Request from `10.0.0.1` with `robot@example.com` → **Not authorized** (neither IP nor token match)

**Security Note**: If both `allowedIps` and `allowedTokens` are empty lists, **all requests are authorized**. This is not recommended for production environments.

## Available Bots

### Session Bot

**Bot Name**: `session`

**Address Pattern**: Typically uses `robotSession` as prefix

**Description**: Analyzes the complete SMTP session and replies with comprehensive diagnostic information.

**Response Includes**:
- Connection information (IP address, rDNS, EHLO/HELO)
- Authentication status and username
- TLS protocol and cipher information
- Envelope details (MAIL FROM, RCPT TO)
- Complete session data as JSON

**Reply-To Address Resolution**:

The Session Bot determines where to send the reply using the following priority:

1. **Sieve reply address**: Uses special format to embed reply address in the bot address itself
   - Format: `robotSession+token+localpart+domain.com@botdomain.com`
   - The reply address is encoded with `+` instead of `@`
   - Everything after the second `+` is the reply address
   - The first `+` in that part becomes the `@`
   - Example: `robotSession+abc+admin+internal.com@example.com` → replies to `admin@internal.com`
2. **Reply-To header**: From the parsed email
3. **From header**: From the parsed email
4. **Envelope MAIL FROM**: From the SMTP envelope

**Example Usage**:

```bash
# Send test email to session bot
echo "Test email" | mail -s "Session Analysis" robotSession@example.com

# With token
echo "Test email" | mail -s "Session Analysis" robotSession+mytoken@example.com

# With custom reply address (sieve format)
echo "Test email" | mail -s "Session Analysis" \
  robotSession+token+admin+mydomain.com@example.com
```

### Email Analysis Bot

**Bot Name**: `email`

**Address Pattern**: Typically uses `robotEmail` or `emailcheck` as prefix

**Description**: Performs comprehensive email security and infrastructure analysis similar to traditional email analysis tools. Checks all major email authentication and security protocols.

**Analysis Includes**:

1. **DNSBL/RBL Check**
   - Checks sender IP against multiple blacklist providers
   - Default RBLs: Spamhaus ZEN, SpamCop, Barracuda, SORBS
   - Reports listing status and response codes

2. **rDNS (Reverse DNS)**
   - Looks up PTR record for sender IP
   - Reports the rDNS hostname or "No rDNS" if missing

3. **FCrDNS (Forward Confirmed Reverse DNS)**
   - Performs forward lookup of rDNS hostname
   - Compares result with original sender IP
   - Reports PASS/FAIL status

4. **SPF (Sender Policy Framework)**
   - Extracts SPF results from Rspamd analysis
   - Reports SPF record and verification result
   - Shows SPF score contribution

5. **DKIM (DomainKeys Identified Mail)**
   - Extracts DKIM results from Rspamd analysis
   - Reports signature presence and verification status
   - Shows which headers were signed

6. **DMARC (Domain-based Message Authentication)**
   - Extracts DMARC results from Rspamd analysis
   - Reports DMARC policy and verification result
   - Shows DKIM/SPF alignment status

7. **MX Records**
   - Lists all MX servers for sender domain
   - Shows priority ordering
   - Uses MXResolver with MTA-STS awareness

8. **MTA-STS (Mail Transfer Agent Strict Transport Security)**
   - Checks for MTA-STS policy
   - Reports policy mode (enforce/testing/none)
   - Lists allowed MX patterns and max age

9. **DANE (DNS-Based Authentication of Named Entities)**
   - Checks for TLSA records on MX hosts
   - Reports DANE enablement status
   - Shows certificate usage, selector, and matching type
   - Displays certificate association data

10. **Virus Scan Results**
    - Reports ClamAV scan results
    - Lists any detected viruses
    - Shows clean/infected status

11. **Spam Analysis**
    - Reports Rspamd spam score and status
    - Lists triggered spam rules with scores
    - Shows detailed symbol breakdown

**Reply-To Address Resolution**:

The Email Analysis Bot uses the same priority as Session Bot:
1. Sieve reply address (embedded in bot address using + encoding)
2. Reply-To header (extracted from envelope)
3. From header (extracted from envelope)
4. Envelope MAIL FROM

**Example Usage**:

```bash
# Send test email to email analysis bot
echo "Test email" | mail -s "Email Analysis" robotEmail@example.com

# With token
echo "Test email" | mail -s "Email Analysis" robotEmail+mytoken@example.com
```

**Example Configuration**:

```json5
{
  bots: [
    {
      addressPattern: "^robotEmail(\\+[^@]+)?@example\\.com$",
      allowedIps: ["192.168.1.0/24"],
      allowedTokens: ["abc12345", "xyz98765"],
      botName: "email"
    }
  ]
}
```

**Report Format**:

The Email Analysis Bot generates a comprehensive plain text report with clearly sectioned results. Each security check is presented with its findings, making it easy to identify authentication failures, security issues, or infrastructure problems.

## Architecture

### Thread Pool

Bot processing uses a cached thread pool (`Executors.newCachedThreadPool()`) to handle requests asynchronously:

- **Non-blocking**: SMTP connections are not held open during bot processing
- **Scalable**: Thread pool grows and shrinks based on demand
- **Multiple bots**: Each bot match is processed in a separate thread

### Thread Safety

Bot processing is designed to be thread-safe:

- **Session Cloning**: The SMTP session and envelope are cloned before being passed to the bot thread pool
- **EmailParser Lifecycle**: The EmailParser is NOT passed to bots to prevent accessing closed resources
- **Header Extraction**: Key email headers (Reply-To, From) are extracted and stored in the envelope before the parser is closed
- **Async Processing**: Bots run asynchronously after the main SMTP transaction completes

This design ensures that bots can safely process emails without holding references to resources that may be closed or modified by the main SMTP thread.

### Health Metrics

Bot pool statistics are available via the `/health` endpoint:

```json
{
  "botPool": {
    "enabled": true,
    "type": "cachedThreadPool",
    "poolSize": 5,
    "activeThreads": 2,
    "queueSize": 0,
    "taskCount": 47,
    "completedTaskCount": 45
  }
}
```

**Metrics**:
- `poolSize`: Current number of threads in the pool
- `activeThreads`: Number of threads actively processing
- `queueSize`: Number of pending bot requests
- `taskCount`: Total number of bot processing tasks
- `completedTaskCount`: Number of completed tasks

## Storage Behavior

Bot addresses have special storage handling:

- **Skipped**: Bot recipient addresses are **not** saved to local mailbox storage
- **Processed**: Bots process the email and generate responses independently
- **Other recipients**: Non-bot recipients in the same email are stored normally
- **Dovecot LDA**: Bot addresses are excluded from Dovecot LDA processing
- **Local Storage**: Bot addresses are skipped when saving to recipient mailboxes

This prevents bot addresses from accumulating in mailboxes while ensuring legitimate recipients still receive their emails.

## Interaction with Other Features

### Blackhole

Bot addresses and blackhole filtering interact as follows:

- **Blackhole Check First**: Blackhole matching is performed before bot address checking
- **Blackholed Bots**: If a bot address matches blackhole rules, it will NOT trigger bot processing
- **No Bot Response**: Blackholed bot addresses are silently discarded without generating any response

This ensures that blackhole rules take precedence and prevents bot abuse through blackholed addresses.

### Proxy

Bot addresses and proxy routing are mutually exclusive:

- **Proxy Takes Priority**: If a recipient matches a proxy rule, it is forwarded to the proxy destination
- **No Bot Processing**: Proxied recipients do not trigger bot processing
- **Separate Flows**: Proxy and bot features operate independently

## Security Considerations

### Preventing Abuse

1. **IP Restrictions**: Limit bot access to trusted networks
2. **Token Authentication**: Use tokens in addresses for validation
3. **Combined Authorization**: Use both IP and token restrictions for defense in depth
4. **Rate Limiting**: Consider implementing rate limiting at the firewall level

### Example Secure Configuration

```json5
{
  bots: [
    {
      addressPattern: "^robotSession(\\+[^@]+)?@example\\.com$",
      allowedIps: ["192.168.1.0/24", "10.0.0.0/8"],
      allowedTokens: ["a1b2c3d4e5f6", "x9y8z7w6v5u4"],
      botName: "session"
    }
  ]
}
```

This configuration:
- Allows requests from internal networks (192.168.1.0/24, 10.0.0.0/8)
- Alternatively, allows requests with valid tokens from any IP
- Provides defense in depth with dual authorization methods

## Implementing Custom Bots

To create a new bot:

1. **Implement BotProcessor interface**:

```java
package com.mimecast.robin.bots;

public class MyCustomBot implements BotProcessor {
    @Override
    public void process(Connection connection, EmailParser emailParser, String botAddress) {
        // Your analysis logic here
    }

    @Override
    public String getName() {
        return "mybot";
    }
}
```

2. **Register in BotFactory**:

Edit `com.mimecast.robin.bots.BotFactory` and add your bot to the static initializer:

```java
static {
    registerBot(new SessionBot());
    registerBot(new MyCustomBot()); // Add your bot here
}
```

Then rebuild the project.

3. **Configure in bots.json5**:

```json5
{
  bots: [
    {
      addressPattern: "^mybot@example\\.com$",
      allowedIps: ["127.0.0.1"],  // Restrict to localhost for testing
      allowedTokens: [],
      botName: "mybot"
    }
  ]
}
```

## Troubleshooting

### Bot Not Triggering

1. **Check pattern matching**: Verify the regex pattern matches your address
2. **Check authorization**: Verify either the source IP is in allowedIps OR the address contains a valid token from allowedTokens
3. **Check IP restrictions**: If using allowedIps, verify the source IP is in the allowed list
4. **Check token**: If using allowedTokens, verify the address contains a valid token
5. **Check logs**: Look for bot detection and authorization messages in server logs

### No Response Received

1. **Check reply address resolution**: Verify Reply-To or From headers exist
2. **Check queue**: Bot responses are queued for delivery
3. **Check relay configuration**: Ensure relay queue is processing
4. **Check logs**: Look for bot processing errors

### Performance Issues

1. **Monitor thread pool**: Check `/health` endpoint for pool statistics
2. **Review bot complexity**: Complex analysis may slow processing
3. **Consider rate limiting**: Too many bot requests can overwhelm the pool

## Examples

### Basic Session Analysis

```bash
# Send email to session bot
cat << EOF | mail -s "Test" robotSession@example.com
This is a test email for session analysis.
EOF
```

### With Custom Reply Address

```bash
# Reply will go to admin@internal.com instead of sender
cat << EOF | mail -s "Test" \
  robotSession+abc123+reply+admin@internal.com@example.com
This is a test email for session analysis.
EOF
```

### Programmatic Usage

```python
import smtplib
from email.message import EmailMessage

msg = EmailMessage()
msg['Subject'] = 'Session Analysis Request'
msg['From'] = 'user@client.com'
msg['To'] = 'robotSession+token123@example.com'
msg.set_content('Please analyze this email.')

with smtplib.SMTP('mail.example.com', 25) as server:
    server.send_message(msg)
```

## Related Documentation

- [Server Configuration](server.md)
- [Health Endpoints](endpoints.md)
- [Queue Management](queue.md)
- [Webhooks](webhooks.md)
