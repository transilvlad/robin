ClamAV Integration
==================

Robin can be configured to scan emails for viruses using ClamAV.

Configuration
-------------

ClamAV integration is configured in the `cfg/clamav.json5` file.

Here is an example `clamav.json5` file:

```json5
{
  clamav: {
    enabled: false,
    host: "localhost",
    port: 3310,

    // Connection timeout in seconds
    timeout: 5,

    // Action to take when a virus is found
    // "reject" - Reject the email with a 5xx error
    // "discard" - Accept the email and discard it
    onVirus: "reject"
  }
}
```

### Options

- **enabled**: A boolean to enable or disable ClamAV scanning. Defaults to `false`.
- **scanAttachments**: A boolean to enable or disable individual attachment scanning. Defaults to `false`.
- **host**: The hostname or IP address of the ClamAV daemon. Defaults to `localhost`.
- **port**: The port number of the ClamAV daemon. Defaults to `3310`.
- **timeout**: The connection timeout in seconds. Defaults to `5`.
- **onVirus**: The action to take when a virus is found.
  - `reject`: Reject the email with a `554 5.7.1 Virus detected` error. This is the default.
  - `discard`: Accept the email but silently discard it.

Programmatic Usage
------------------

The `ClamAVClient` class provides a simple way to interact with the ClamAV daemon.

### Creating a Client

You can create a `ClamAVClient` instance with the default constructor, which uses `localhost:3310`.

```java
ClamAVClient clamAVClient = new ClamAVClient();
```

Or you can specify the host and port.

```java
ClamAVClient clamAVClient = new ClamAVClient("clamav.example.com", 3310);
```

### Scanning

The client can scan files, byte arrays, and input streams.

#### Scanning a File

```java
File file = new File("/path/to/email.eml");
boolean infected = clamAVClient.isInfected(file);

if (infected) {
    System.out.println("Viruses found: " + clamAVClient.getViruses());
}
```

#### Scanning a Byte Array

```java
byte[] data = ...;
ScanResult result = clamAVClient.scanBytes(data);

if (result instanceof ScanResult.VirusFound) {
    System.out.println("Viruses found: " + ((ScanResult.VirusFound) result).getFoundViruses());
}
```

#### Scanning an Input Stream

```java
InputStream inputStream = ...;
ScanResult result = clamAVClient.scanStream(inputStream);

if (result instanceof ScanResult.VirusFound) {
    System.out.println("Viruses found: " + ((ScanResult.VirusFound) result).getFoundViruses());
}
```
