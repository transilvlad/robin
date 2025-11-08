Robin MTA Server and Tester
===========================
By **Vlad Marian** *<transilvlad@gmail.com>*


Overview
--------
<img align="right" width="200" height="200" src="doc/img/robin.jpg" alt="Logo">
Robin MTA Server and Tester is a development, debug and testing tool for MTA architects.

However, as the name suggests it can also be used as a lightweight MTA server with Dovecot SASL AUTH and mailbox integration.

It is powered by a highly customizable SMTP client designed to emulate the behaviour of popular email clients.

The lightweight server is ideal for a simple configurable catch server for testing or a fully fledged MTA using Dovecot mailboxes or web hooks.
Provides Prometheus and Graphite metrics with Prometheus remote write built in plus a multitude or other handy endpoints.

The testing support is done via JSON files called test cases.
Cases are client configuration files ran as Junit tests leveraging CI/CD integrations.

This project can be compiled into a runnable JAR or built into a Docker container as a stand alone MTA or combined with Dovecot.

A CLI interface is implemented with support for client, server and MTA-STS client execution.
Robin makes use of the single responsibility principle whenever possible providing stand-alone tools and libraries most notably an MTA-STS implementation.

Use this to run end-to-end tests manually or in automation.
This helps identify bugs early before leaving the development and staging environments.

Or set up the MTA server and configure it with for your mailbox hosting, API infrastructure or bespoke needs.
Best do both since Robin is both an MTA and MTA tester :)

Contributions
-------------
Contributions of any kind (bug fixes, new features...) are welcome!
This is a development tool and as such it may not be perfect and may be lacking in some areas.

Certain future functionalities are marked with TODO comments throughout the code.
This however does not mean they will be given priority or ever be done.

Any merge request made should align to existing coding style and naming convention.
Before submitting a merge request please run a comprehensive code quality analysis (IntelliJ, SonarQube).

Read more [here](contributing.md).


Disclosure
----------
This project makes use of sample password as needed for testing and demonstration purposes.

- notMyPassword - It's not my password. It can't be as password length and complexity not met.
- 1234 - Sample used in some unit tests.
- giveHerTheRing - Another sample used in unit tests and documentation. (Tony Stark / Pepper Potts Easter egg)
- avengers - Test keystore password that contains a single entry issued to Tony Stark. (Another Easter egg)

**These passwords are not in use within production environments.**


Documentation
-------------
- [Introduction](doc/introduction.md)
- [CLI usage](doc/cli.md)

### Java SMTP/E/SMTP/LMTP Client
- [Client usage](doc/client.md)

### Server
- [Server configuration](doc/server.md)
- [HashiCorp Vault](doc/vault.md)
- [SMTP webhooks](doc/webhooks.md)
- [Endpoints](doc/endpoints.md) - JVM metrics implementation.
- [Prometheus Remote Write](doc/prometheus.md) - Prometheus Remote Write implementation.
- [ClamAV Integration](doc/clamav.md) - ClamAV virus scanning integration.
- [Rspamd Integration](doc/rspamd.md) - Rspamd spam/phishing detection integration.

### Secrets
- [Secrets, magic and Local Secrets File](doc/secrets.md)

### Testing cases
- [E/SMTP Cases](doc/case-smtp.md)
- [HTTP/S Cases](doc/case-http.md)
- [Magic](doc/magic.md)
- [MIME](doc/mime.md)

### Server Library
- [Plugins](doc/plugins.md)
- [Flowchart](doc/flowchart.md)

### Libraries
- [MTA-STS](doc/lib/mta-sts/readme.md) - MTA-STS compliant MX resolver implementation (former MTA-STS library).
- [Dovecot SASL](doc/lib/dovecot-sasl.md) - Dovecot SASL authentication implementation.
- [IMAP helper](doc/lib/imap.md) - Lightweight Jakarta Mail IMAP client.
- [MIME Parsing and Building](doc/lib/mime.md) - Lightweight RFC 2822 email parsing and composition.
- [MIME Header Wrangler](doc/lib/header-wrangler.md) - MIME header content injector for tagging and adding headers.
- [HTTP Request Client](doc/lib/request.md) - Lightweight HTTP request client.

### Miscellaneous
- [Contributing](contributing.md)
- [Code of conduct](code_of_conduct.md)

_Robin is designed with single responsibility principle in mind and thus can provide reusable components for various tasks._
