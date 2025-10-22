Robin MTA Server and Tester
================
By **Vlad Marian** *<transilvlad@gmail.com>*


Overview
--------
<img align="right" width="200" height="200" src="doc/logo.jpg" alt="Logo">
Robin MTA Server and Tester is a development, debug and testing tool for MTA architects.
However as the name suggests it can also be used as a lightweight MTA server with Dovecot AUTH and mailbox integration support.

It is powered by a highly customizable SMTP client designed to emulate the behaviour of popular email clients.
The lightweight server is ideal for a simple configurable catch server for testing or a fully fledged MTA on using Dovecot mailboxes.

The primary usage is done via JSON files called test cases.
Cases are client configuration files ran as Junit tests.

This project can be compiled into a runnable JAR or built into a Docker container.

A CLI interface is implemented with support for both client and server execution.

Use this to run end-to-end tests manually or in automation.
This helps identify bugs early before leaving the development and staging environments.

Or set up a lightweight MTA server for your development and testing needs.
Or both :)

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
- giveHerTheRing - Another sample used in unit tests and documentation. (Tony Stark / Pepper Pots Easter egg)
- avengers - Test keystore password that contains a single entry issued to Tony Stark. (Another Easter egg)

**These passwords are not in use within our production environments.**


Documentation
-------------
- [Introduction](doc/introduction.md)
- [CLI usage](doc/cli.md)

### Java SMTP/E/SMTP/LMTP Client
- [Client usage](doc/client.md)

### MTA Server
- [Server configuration](doc/server.md)
- [SMTP webhooks](doc/webhooks.md)
- [Endpoints](doc/endpoints.md) - Reusable stand-alone JVM metrics implementation.
- [Prometheus Remote Write](doc/prometheus.md) - Reusable Prometheus Remote Write implementation.

### Testing cases
- [E/SMTP Cases](doc/case.md)
- [HTTP/S Cases](doc/case.md)
- [Magic](doc/magic.md)
- [MIME](doc/mime.md)

### Server Library
- [Plugins](doc/plugins.md)
- [Flowchart](doc/flowchart.md)

### MTA-STS Library
- [MTA-STS](doc/mta-sts/readme.md) - Former MTA-STS library (stand-alone implementation).

### Miscellaneous
- [Contributing](contributing.md)
- [Code of conduct](code_of_conduct.md)
