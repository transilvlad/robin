E/SMTP Cases
============
Cases represent specific scenarios that can be configured in JSON and executed as a test for end-to-end testing of MTA & WEB servers.


Glossary
--------

### Session
#### Connection
- `retry` - _(Integer, Attempts)_ [default: 1] How many times to attempt connection.
- `delay` - _(Integer, Seconds)_ [default: 0] Delay between connection retries.
- `timeout` - _(Integer, Milliseconds)_ [default: Java] Socket timeout.


#### Destination
- `mx` - _(List, String, IP, FQD)_ [default: 127.0.0.1] MX server list.
- `port` - _(Integer)_ [default: 25] MX port.
- `route` - _(String, Name)_ [default: none] Selects a preconfigured route by name from _client.json5_ routes if any.


#### Encryption
- `tls` - _(Boolean)_ [default: false] Allows the client to do TLS when _STARTTLS_ is advertised.
- `protocols` - _(List, String)_ [default: Java] TLS protocols to support.
- `ciphers` - _(List, String)_ [default: Java] TLS ciphers to support.


#### Authentication
- `auth` - _(Boolean)_ [default: false] SMTP authentication.
- `user` - _(String)_ Authentication username.
- `pass` - _(String)_ Authentication password.


#### Authentication Config
- `authBeforeTls`  - _(Boolean)_ [default: false] Send _AUTH_ without requiring TLS. **_Vulnerable behaviour._**
- `authLoginCombined`  - _(Boolean)_ [default: false] Send username and password in one line for _AUTH LOGIN_.
- `authLoginRetry`  - _(Boolean)_ [default: false] Disable authLoginCombined and retry _AUTH LOGIN_.


#### HELO/LHLO/EHLO
HELO takes precedence over the others, LHLO only over EHLO.
- `helo` - _(String, IP, FQD)_ [default: hostname] _HELO_ domain.
- `lhlo` - _(String, IP, FQD)_ [default: hostname] _LHLO_ domain.
- `ehlo` - _(String, IP, FQD)_ [default: hostname] _EHLO_ domain.


#### XCLIENT
- `xclient` - _(SMTP command)_ Special feature allowing enumeration of client info:
  - addr - IP address.
  - name - Reverse DNS.
  - helo - HELO/EHLO domain.


### Envelope
- `mail` - _(String, Email Address)_ [default: client.json5] Sender email address.
- `rcpt` - _(List, String, Email Address)_ [default: client.json5] Recipients list of email addresses.

- `params` - Allows usage of custom parameters for special environments.

Will auto-inject list elements at the end of the MAIL or RCPT commands separated by space.

**Example case config:**

        params: {
            MAIL: [ "XOORG=example.com" ],
            RCPT: [ "ACCEPT" ]
        },

- `headers` - _(List, String, String)_ List of headers to be injected by magic.

Handy for EJF automation and more.

**Example case config:**

This uses magic client variables. See [magic.md](../features/magic.md).

        headers: {
            from: "{$mail}",
            to: [ "{$rcpt}" ],
            sender: "{$mail}",
            recipient: [ "{$rcpt}" ]
        },

Can be injected in the eml via `{$HEADERS}` magic variable or selective by providing the key like `{$HEADERS[FROM]}`.


#### Transfer
##### BDAT Config
- `chunkSize` - _(Integer, Bytes)_ [default: 2048, min: 128] Enables _CHUNKING_ if grater than minimum.
- `chunkBdat`  - _(Boolean)_ [default: false] Writes _BDAT_ command to socket along with the first chunk.
- `chunkWrite` - _(Boolean)_ [default: false] Writes to socket in uneven chunks between 1024 and 2048 bytes if _chunkSize_ at least 2048.


##### DATA Config
_Only one may be used at the same time (Order of priority)._
- `terminateAfterBytes` - _(Integer, Bytes)_ [default: 0, min: 1] Terminates connection after transferring given bytes of _DATA_ when greater. Enables _terminateBeforeDot_.
- `terminateBeforeDot` - _(Boolean)_ [default: false] Terminates connection right before transferring _DATA_ terminator &lt;CRLF&gt;.&lt;CRLF&gt;.
- `terminateAfterDot` - _(Boolean)_ [default: false] Terminates connection right after transferring _DATA_ terminator &lt;CRLF&gt;.&lt;CRLF&gt;.


##### Speed
- `slowBytes` - _(Integer, Bytes)_ [default: 1, min: 128]  Write delay every n number of bytes.
- `slowWait` - _(Integer, Milliseconds)_ [default: 0, min: 100]  Write delay wait time in milliseconds.


##### Load
- `repeat` - _(Integer, Times)_ [default: 0]  How many times to resend the same envelope after the first time. Will stop if any delivery fails.


#### Message (envelope)
Every message should have either a file or a subject/message pair!
If a file is not defined a MIME source will be generated from envelope date along with subject and message.
While message is mandatory subject may be left blank.

- `file` - _(String, Path)_ Path to eml file to be transmitted.
- `folder` - _(String, Path)_ Path to eml containing folder. A random eml file will be chosen for each DATA transmission.
- `subject` - _(String)_ Email subject.
- `message` - _(String)_ Email text/plain message.


Protocol Assertions
---------------
Regex assertions to be run against SMTP transactions.
There are session level and envelope level assertions.

#### Session
- SMTP - Initial connection response.
- EHLO
- STARTTLS
- TLS - Successful handshake protocol / cipher.
- SHLO - Post STARTTLS EHLO response.
- XCLIENT
- XHLO - Post XCLIENT EHLO response.
- AUTH
- RSET
- HELP
- QUIT

        [ "SMTP", "^220" ],
        [ "EHLO", "STARTTLS" ],
        [ "STARTTLS", "^220" ],
        [ "TLS", "^TLSv1.2:TLS_RSA_WITH_AES_256_GCM_SHA384" ],
        [ "SHLO", "250 HELP" ]
        [ "AUTH", "^235" ]
        [ "QUIT", "^221" ]


#### Envelope
- MAIL
- RCPT
- DATA/BDAT - Mutually exclusive.

        [ "MAIL", "^250" ],
        [ "RCPT", "^250" ],
        [ "DATA", "^250" ],
        [ "DATA", "Received OK$" ],
        [ "BDAT", "^250" ],

#### Forms and selectors
Regexes are case-insensitive and match anywhere in the reply unless anchored.

- `[ COMMAND ]` - A transaction for that command exists.
- `[ COMMAND, regex ]` - At least one transaction for that command matches.
- `[ COMMAND, regex, "all" ]` - Every transaction for that command matches.
- `[ COMMAND, regex, "none" ]` - No transaction for that command matches. Passes when the command was never sent.
- `[ COMMAND, regex, index ]` - The transaction at that zero-based index exists and matches.

Selectors matter for commands that repeat in one envelope: `RCPT`, `BDAT` and `LMTP`.

        [ "RCPT", "^250", "all" ],   // Every recipient accepted.
        [ "RCPT", "^550", 1 ],       // The second recipient rejected.
        [ "DATA", "^250", "none" ]   // The message was never accepted.

Any other form is a configuration error and fails the case, whatever `protocolFails` is set to.
With `protocolFails: false` a failed assertion skips the remaining assertions instead of failing.


MTA Assertions
---------------
You may run assertions against your MTA logs from envelope level.
A local logs client is provided to demonstrate.

_See LogsClient.java interface for implementation of external clients._

- `logPrecedence` - _(String)_ Prepends provided string to the log filename which is otherwise `yyyyMMdd`.log.
- `wait` - _(Integer, Seconds)_ [default: 2, min: 2] Initial wait before calling the external client.
- `retry` - _(Integer, Attempts)_ [default: 1, min: 1] How many times to fetch logs until `verify` matches.
- `delay` - _(Integer, Attempts)_ [default: 2, min: 2] Delay between attempts.
- `grep` - _(List of Map, String, Regex)_ Line filters; a line is kept when it passes every filter. Without `grep` lines containing the envelope transaction UID are kept.
  - `parameter: "E"` compiles the pattern as a regex instead of a literal.
  - `parameter: "v"` keeps lines that do not match.
  - `parameter: "c"` replaces the kept lines with their count, for example `verify: [ "^2$" ]`.
- `verifyNone` - _(Boolean)_ Fails if any line is found on any attempt. Disables all below functionalities.
- `verify` - _(List, String, Regex)_ Patterns that must all match one line before the logs are accepted. Provides stability when the MTA takes more time. Fails when attempts are spent.
- `match` - _(List of List, String, Regex)_ Every group must have all its patterns found. Patterns in one group may match different lines, so use one regex per group to check a single line.
- `refuse` - _(List of List, String, Regex)_ The opposite of match. Fails when all patterns of a group are found.
- `magic` - _(List of Map, String, Regex)_ Collect matching group 1 or whole patterns into magic variable. The first match in this assertion replaces any earlier value.

Failures throw unless the case sets `verifyFails: false`, which skips the remaining assertions instead.

### Basic example.

        type: "logs",
        logPrecedence: "fast-",
        wait: 10,
        retry: 2,
        delay: 5,
        grep: [
          {
            // Default match on UID.
            pattern: "{$uid}"
          },
          {
            // Exclude DEBUG and TRACE log lines.
            parameter: "-vE",
            pattern: "DEBUG|TRACE"
          }
        ]
        verify: [ "MAPREDUCE:RCPT" ],
        match: [
            [ "250", "Sender OK" ]
            [ "250", "Recipient OK" ]
            [ "MAPREDUCE:RCPT", "Custody=true", "Storage=check" ]
            [ "250", "Received OK" ]
        ],
        refuse: [
            [ "java.lang.NullPointerException" ]
        ]

### Example for verify none.

Fails if this transaction logged an error or exception within the wait window.

        type: "logs",
        wait: 10,
        grep: [
          {
            pattern: "{$transactionUid}"
          },
          {
            parameter: "-E",
            pattern: "ERROR|Exception"
          }
        ]
        verifyNone: true


#### Request

`type: "request"` makes an HTTP call and asserts against the response, for example the Robin `/logs` API.
JSON bodies are flattened into `path: value` lines; other bodies are split into lines.
It takes `wait`, `retry`, `delay`, `verify`, `verifyNone`, `match`, `refuse` and `magic` with the same meaning as above, plus:

- `request` - _(Map)_ The HTTP request, as in [case-http.md](case-http.md).
- `status` - _(Integer)_ Exact HTTP status expected. Default: any 2xx.
- `paths` - _(List, String)_ Keep only flattened JSON lines starting with these paths.

Each attempt is retried until the status is acceptable and `verify` matches.

        type: "request",
        wait: 2,
        delay: 2,
        retry: 5,
        request: {
          url: "http://127.0.0.1:28090/logs?q={$transactionUid}",
          type: "GET"
        },
        verify: [ "LMTP delivery successful" ],
        refuse: [
          [ "Enqueuing failed mailbox" ]
        ]


#### IMAP

`type: "imap"` fetches the envelope's message by Message-ID, so the eml must use `{$MSGID}` in its Message-ID header.
See [imap.md](../lib/imap.md) for all options. It retries until the message is found and matches.
`absent: true` instead fails if the message is found on any attempt; use it to prove a message was not delivered.


#### Humio

With the addition of Humio plugin you can simply use `type: "humio",` instead of logs to pull logs from configured Humio instance.

##### Humio config in properties.json

        humio: {
            auth: "YOUR_API_KEY",
            url: "https://humio.example.com/"
        }


Case
----

    {
        // Magic variables.
        $: { // Use the dollar sign to define a map to hold them.
            fromAddress: "robin@example.come",
            toUser: "smoke",
            toDomain: "example.net",
            toAddress: "{$toUser}@{$toDomain}" // Can be used immediatelly after defined above.
        },
        
        // MX list and port to attempt to deliver the email to.
        mx: [
            "example.com"
        ],
        port: 25,

        // How many times to try and establish a connection to remote server. First counts.
        retry: 2,

        // Delay between tries.
        delay: 5,
    
        // Enable TLS.
        tls: true,

        // Set supported protocols.
        protocols: [
            "TLSv1.2", "TLSv1.3"
        ],

        // Set EHLO to use.
        ehlo: "example.com",

        // Enable authentication.
        auth: true,
        user: "tony@example.com",
        pass: "stark",


      // Email envelopes.
      envelopes: [
        // Envelope one.
        {
                // Configure chunking parameters to use if CHUNKING supported by recipient server.
                chunkSize: 20480,
                chunkBdat: true,
                chunkWrite: true,

                // Magic sender and recipient.
                mail: "{$fromAddress}",
                rcpt: [
                    "{$toAddress}"
                ],

                // Set custom params to send with MAIL and/or RCPT.
                params: {
                    MAIL: [ "XOORG=example.com" ],
                    RCPT: [ "ACCEPT" ]
                },

                // Email eml file to transmit.
                file: "src/test/resources/cases/sources/lipsum.eml",
      
                // Configure early email transfer termination.
                terminateAfterBytes: 1024,
                terminateBeforeDot: true,
                terminateAfterDot: true,

                // Assertions to run against the envelope.
                assertions: {
          
                    // Protocol assertions.
                    // Check SMTP responses match regular expressions.
                    protocol: [
                        [ "MAIL", "^250" ],
                        [ "RCPT", "^250" ],
                        [ "BDAT", "^250" ]
                    ],
    
                    // External assertions.
                    external: [
                      {
                        // Assertion type logs for pulling logs.
                        type: "logs",
            
                        // How long to wait before asserting.
                        wait: 10,
            
                        // How many times to retry should no logs be found or valid.
                        retry: 2,
            
                        // Delay between log pulling attempts.
                        delay: 5,

                        // Grep command to use to fetch logs.
                        grep: [
                          {
                            // Default match on UID.
                            pattern: "{$uid}"
                          },
                          {
                            // Exclude DEBUG and TRACE log lines matched.
                            parameter: "-vE",
                            pattern: "DEBUG|TRACE"
                          }
                        ],
            
                        // Verify regular expressions that would confirm logs fetched are complete.
                        verify: [ "MAPREDUCE:RCPT" ],
            
                        // Match regular expressions to run agaisnt logs to pass the test.
                        match: [
                            [ "250", "Sender OK" ]
                            [ "250", "Recipient OK" ]
                            [ "MAPREDUCE:RCPT", "Custody=true", "Storage=check" ]
                            [ "250", "Received OK" ]
                        ]

                        // Magic regular expressions that will record data in Session magic for use in following assertions.
                        // This will record group 1 is there is one else full match.
                        magic: [
                          {
                            name: "ruid",
                            pattern: "Received OK \\[(.*)\\]"
                          }
                        ]
                      }
                    ]
                }
            }
        ],

        // Assertions to run against the connection.
        assertions: {
            // Asserting configuration.
            protocolFails: false, // If SMTP assertion fails, fail test/exit gracefully.
            verifyFails: false, // If external verify checks fail, fail test/exit gracefully.
        
            // Protocol assertions.
            // Check SMTP responses match regular expressions.
            protocol: [
                [ "SMTP", "^220" ],
                [ "EHLO", "STARTTLS" ],
                [ "SHLO", "250 HELP" ]
            ]
        }
    }


Maven execution
----------------
Example commandline for running cases.

    mvn clean -D test="cases.*" test
