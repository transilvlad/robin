MTA-STS
=======

Overview
--------
<img align="right" width="200" height="200" src="../img/mta-sts.png">
SMTP MTA Strict Transport Security

This is a Java implementation of MTA-STS with support for TLSRPT record fetching.

The library does not provide a production ready trust manager or policy cache.
A X509TrustManager implementation needs to be provided and should enable revocation checks.
An abstract PolicyCache is provided to aid in integrating with your cloud cache. 

This project can be compiled into a runnable JAR.
A CLI interface is implemented.

Best practices
--------------
The following validations are off by default:
- `Require HTTPS response Content-Type as text/plain.`

In practice, we see policies that will not have it or have a different value.

- `Require policy line endings as CRLF.`

While the policy states: `This resource contains the following CRLF-separated key/value pairs`
but in the ABNF you see: `sts-policy-term          = LF / CRLF`

Usage
-----
- [Library usage](lib.md)
- [CLI usage](../cli.md)

- RFC Excerpts
------------
- [MTA-STS](mta-sts.md)
- [TLSRPT](tlsrpt.md)
