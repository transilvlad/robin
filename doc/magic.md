Magic client
============

Case files inherit the default config from client.json5.
However, in some cases you may want to use the defaults in some cases.
For those cases you may use the following magic variables in case files.

- `{$mail}` - This always references the client.json5 mail param value if any.
- `{$rcpt}` - This always references the client.json5 rcpt param first value if any.


Magic session
=============

The Session object has a magic store where it loads up CLI params, properties file data and data from external assertions.
It can also be seeded using a `$` variable in the case file like so:

    $: {
        fromUser: "robin",
        fromDomain: "example.com",
        fromAddress: "{$fromUser}@{$fromDomain}",
        toUser: "lady",
        toDomain: "example.com",
        toAddress: "{$toUser}@{$toDomain}",
    }

Lastly the session also contains the following:
- `{$uid}` - The Session uid used in logging and storage file path.
- `{$yymd}` - Date in `yyyyMMdd` format and storage file path.

All of these can be used through the case files to aid testing automation.


Magic eml
=========

Email (.eml) files may contain these magic variables.
Use these to program your emails to autocomplete information.

- `{$DATE}` - RFC compliant current date.
- `{$YYMD}` - YYYYMMDD date.
- `{$YEAR}` - Current year.
- `{$MSGID}` - Random string. Combines with {$MAILFROM} to form a valid Message-ID.
- `{$MAILFROM}` - Envelope mail address.
- `{$MAIL}` - Envelope mail address.
- `{$RCPTTO}` - Envelope rcpt address.
- `{$RCPT}` - Envelope rcpt address.
- `{$HEADERS}` - Magic headers.
- `{$HEADERS[*]}` - Magic header by name.
- `{$RANDNO}` - Random number between 1 and 10.
- `{$RANDCH}` - Random 20 alpha characters.
- `{$RANDNO#}` - Generates random number of given length (example: `{$RANDNO3}`).
- `{$RANDCH#}` - Random alpha characters of given length (example: `{$RANDCH15}`).
- `{$HEADERS}` - Add all custom headers.
- `{$HEADERS[#]}` - Add header value by key (example: `{$HEADERS[FROM]}`).

If you wish to prepend headers to an email you can set `prependHeaders` boolean to `true`.
_(This can result in duplicate headers when magic headers from above are used with an eml from file.)_  

    envelopes: [
      headers: {
        "x-example-one": "1",
        "x-example-two": "2"
      },
      prependHeaders: true,
      file: "/path/to/eml/file.eml"
    }


Magic eml headers
=================

The following headers will enable additional functionalities within the Robin server component upon receipt.

- `X-Robin-Filename` - If a value is present and valid filename, this will be used to rename the stored eml file.
- `X-Robin-Relay` - If a value is present and valid server name and optional port number email will be relayed to it post receipt.
- `X-Robin-Chaos` - If present and chaos headers are enabled, allows bypassing normal processing for testing exception scenarios.


Magic chaos headers
===================

The `X-Robin-Chaos` header allows testing exception scenarios by bypassing normal processing and returning predefined results.

**WARNING:** This feature is intended for testing purposes only. Do NOT enable in production environments.

To enable chaos headers, set `chaosHeaders: true` in `server.json5`.

The header value format is: `ClassName; param1=value1; param2=value2`

Where:
- `ClassName` - The implementation class where the action occurs (e.g., `LocalStorageClient`, `DovecotLdaClient`).
- Parameters define the bypass behavior specific to each implementation.

Multiple chaos headers can be present in the same email to test different scenarios.

### Implementation examples

**Bypass any storage processor:**
```
X-Robin-Chaos: LocalStorageClient; processor=AVStorageProcessor; return=true
X-Robin-Chaos: LocalStorageClient; processor=SpamStorageProcessor; return=false
X-Robin-Chaos: LocalStorageClient; processor=LocalStorageProcessor; return=true
```

This bypasses the call to the specified storage processor. The `processor` parameter should match the processor class name (e.g., `AVStorageProcessor` for virus scanning, `SpamStorageProcessor` for spam scanning, `LocalStorageProcessor` for local mailbox storage, `DovecotStorageProcessor` for Dovecot LDA delivery). The `return` parameter specifies what value to return from the bypass (`true` to continue processing, `false` to stop with error).

**Simulate Dovecot LDA failure:**
```
X-Robin-Chaos: DovecotLdaClient; recipient=tony@example.com; exitCode=1; message="storage full"
```

This bypasses the actual Dovecot LDA call for the specified recipient and returns the predefined result:
- Exit code: `1` (failure)
- Error message: `"storage full"`

The `exitCode` parameter is an integer and the `message` parameter contains the error message. Quotes are optional for the message parameter unless it contains special characters.
