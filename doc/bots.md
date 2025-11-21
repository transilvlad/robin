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
      
      // List of domains allowed to trigger this bot
      // Empty list means all domains are allowed
      domains: [
        "example.com",
        "test.com"
      ],
      
      // List of IP addresses or CIDR blocks allowed to trigger this bot
      // This prevents abuse by restricting who can use the bot
      // Empty list means all IPs are allowed
      allowedIps: [
        "127.0.0.1",
        "::1",
        "192.168.1/24",
        "10.0.0.0/8"
      ],
      
      // Name of the bot implementation to use from the factory
      // Currently supported: "session"
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

#### Domain Restrictions

The `domains` array restricts which domains can trigger the bot:

- **Empty list**: All domains allowed (default)
- **Specific domains**: `["example.com", "test.com"]`
- **Case insensitive**: Domain matching is case-insensitive

#### IP Address Restrictions

The `allowedIps` array restricts which source IPs can trigger the bot:

- **Empty list**: All IPs allowed (default)
- **Individual IPs**: `["127.0.0.1", "::1"]`
- **CIDR notation**: `["192.168.1/24", "10.0.0.0/8"]`

**Note**: The CIDR implementation uses simple prefix matching by extracting the portion before the "/" and checking if the IP starts with that prefix. For example, `192.168.1/24` matches any IP starting with `192.168.1`. This is sufficient for most internal use cases but does not perform proper subnet mask calculations. For production environments requiring strict CIDR validation, consider using a dedicated IP address library.

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
   - Format: `robotSession+token+reply+username@replydomain.com@example.com`
   - The portion between `+reply+` and the final `@` is extracted as the reply email
   - Example: `robotSession+abc+reply+admin@internal.com@example.com` → replies to `admin@internal.com`
2. **Reply-To header**: From the parsed email
3. **From header**: From the parsed email  
4. **Envelope MAIL FROM**: From the SMTP envelope

**Example Usage**:

```bash
# Send test email to session bot
echo "Test email" | mail -s "Session Analysis" robotSession@example.com

# With token
echo "Test email" | mail -s "Session Analysis" robotSession+mytoken@example.com

# With custom reply address
echo "Test email" | mail -s "Session Analysis" \
  robotSession+token+reply+admin@mydomain.com@example.com
```

## Architecture

### Thread Pool

Bot processing uses a cached thread pool (`Executors.newCachedThreadPool()`) to handle requests asynchronously:

- **Non-blocking**: SMTP connections are not held open during bot processing
- **Scalable**: Thread pool grows and shrinks based on demand
- **Multiple bots**: Each bot match is processed in a separate thread

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

This prevents bot addresses from accumulating in mailboxes while ensuring legitimate recipients still receive their emails.

## Security Considerations

### Preventing Abuse

1. **IP Restrictions**: Limit bot access to trusted networks
2. **Domain Restrictions**: Only allow specific domains to trigger bots
3. **Token Addressing**: Use tokens in addresses for additional validation
4. **Rate Limiting**: Consider implementing rate limiting at the firewall level

### Example Secure Configuration

```json5
{
  bots: [
    {
      addressPattern: "^robotSession\\+[a-f0-9]{32}@example\\.com$",
      domains: ["example.com"],
      allowedIps: ["192.168.1/24", "10.0.0/8"],
      botName: "session"
    }
  ]
}
```

This configuration:
- Requires a 32-character hex token
- Only allows example.com domain
- Restricts to internal networks

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
      domains: [],
      allowedIps: [],
      botName: "mybot"
    }
  ]
}
```

## Troubleshooting

### Bot Not Triggering

1. **Check pattern matching**: Verify the regex pattern matches your address
2. **Check domain restrictions**: Ensure the sender domain is allowed
3. **Check IP restrictions**: Verify the source IP is in the allowed list
4. **Check logs**: Look for bot detection messages in server logs

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
