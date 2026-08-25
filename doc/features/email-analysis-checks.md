# Email Analysis Bot Checks

The Email Analysis Bot is a message-centric deliverability and security report. It analyzes the SMTP session that delivered the bot request, the envelope/header domains in the message, DNS records for those domains, Rspamd authentication results, and the receiving MX infrastructure for the message domains.

It is not a general domain audit. Checks are included when they explain whether the observed message was authenticated, whether the domain's advertised mail exchangers are reachable and RFC-correct, or whether common operational signals indicate deliverability risk.

## Report Output

The bot replies with a multipart report:

- `text/plain` for mail clients, automation, and logs.
- `text/html` for human review, with colored status badges for `PASS`, `WARN`, `FAIL`, `ERROR`, `INFO`, and `SKIPPED`.

Each check includes a category, status, summary, evidence, remediation where applicable, and RFC or operational references.

## Standards Scope

| Area | Source | How Robin Uses It |
|------|--------|-------------------|
| SMTP session identity and delivery | [RFC 5321](https://www.rfc-editor.org/info/rfc5321/) | EHLO/HELO, reverse DNS identity evidence, MX routing, port 25 reachability, `postmaster@` expectations. |
| Internet message headers | [RFC 5322](https://www.rfc-editor.org/info/rfc5322/) | Presence of common authoring and deliverability headers such as `From`, `Date`, `Message-ID`, and `Subject`. |
| Role mailboxes | [RFC 2142](https://www.rfc-editor.org/info/rfc2142/) | Optional probes for mail-relevant administrative aliases such as `abuse@`. |
| MX target correctness | [RFC 2181](https://www.rfc-editor.org/info/rfc2181/) | MX targets must be canonical names with address records, not CNAME aliases or address literals. |
| STARTTLS | [RFC 3207](https://www.rfc-editor.org/info/rfc3207/) | Reports whether SMTP hosts advertise/complete STARTTLS and whether certificate expiry is near. |
| SPF | [RFC 7208](https://www.rfc-editor.org/info/rfc7208/) | Interprets Rspamd SPF symbols as pass/warn/fail/error outcomes. |
| DKIM | [RFC 6376](https://www.rfc-editor.org/info/rfc6376/) | Interprets Rspamd DKIM symbols and verifies referenced selector TXT records exist. |
| DMARC | [RFC 7489](https://www.rfc-editor.org/info/rfc7489/) | Interprets Rspamd DMARC symbols and checks `_dmarc` DNS publication. |
| DANE for SMTP | [RFC 7672](https://www.rfc-editor.org/info/rfc7672/) | Checks whether MX hosts publish TLSA records for SMTP. |
| TLS reporting | [RFC 8460](https://www.rfc-editor.org/info/rfc8460/) | Checks `_smtp._tls` TLSRPT policy publication. |
| MTA-STS | [RFC 8461](https://www.rfc-editor.org/info/rfc8461/) | Checks MTA-STS TXT discovery and HTTPS policy validity for the message domain. |
| List unsubscribe | [RFC 2369](https://www.rfc-editor.org/info/rfc2369/) and [RFC 8058](https://www.rfc-editor.org/info/rfc8058/) | Checks list-like messages for unsubscribe and one-click unsubscribe headers. |
| RDAP domain registration data | [RFC 9082](https://www.rfc-editor.org/info/rfc9082/) and [RFC 9083](https://www.rfc-editor.org/info/rfc9083/) | Optional domain-age reputation signal. This is not an SMTP correctness requirement. |

## Check Categories

### Sending Host

The bot reports the observed connecting IP, EHLO/HELO name, PTR name, EHLO forward addresses, PTR forward addresses, whether PTR is forward-confirmed, and whether EHLO matches PTR.

Expected healthy state:

- EHLO/HELO is a syntactically valid FQDN or valid address literal.
- EHLO forward DNS includes the connecting IP.
- PTR exists and forward DNS for the PTR includes the connecting IP.
- EHLO and PTR are aligned where possible.

The EHLO/PTR result is visible in both plain text and HTML. A mismatch is reported as a deliverability warning rather than a hard protocol failure unless the EHLO syntax itself is invalid.

### Reputation

The bot checks:

- Connecting IP against configured DNSBL/RBL providers.
- PTR hostname and, when it is a subdomain, its apex domain against configured DBL/SURBL-style providers.
- Message, DKIM, and EHLO domains against configured DBL/SURBL-style providers.
- Optional domain age over RDAP when `domainAgeCheckEnabled` is true.

DNSBL and DBL lists are fully configurable through bot configuration. Defaults are examples only and should be reviewed for the deployment's policy and query entitlement.
PTR DBL results are reported immediately after the connecting IP DNSBL result, followed by the sending-domain DBL results.

Domain age is deliberately disabled by default. Very new domains can be useful reputation context, but domain age is not an RFC-defined deliverability correctness rule.

### Authentication

SPF, DKIM, and DMARC verdicts come from Rspamd scan results stored on the envelope. Robin translates Rspamd symbols into explicit report statuses so the user does not have to know Rspamd symbol names.

SPF mapping:

| Rspamd Symbol | Report Status | Meaning |
|---------------|---------------|---------|
| `R_SPF_ALLOW` | `PASS` | SPF passed. |
| `R_SPF_FAIL` | `FAIL` | SPF hard failed. |
| `R_SPF_SOFTFAIL` | `WARN` | SPF soft failed. |
| `R_SPF_NEUTRAL` | `WARN` | SPF returned neutral; this is not a pass. |
| `R_SPF_DNSFAIL` | `ERROR` | SPF had a DNS temporary failure. |
| `R_SPF_PERMFAIL` | `FAIL` | SPF policy has a permanent error. |
| `R_SPF_NA` | `WARN` | No SPF policy was found. |
| `R_SPF_PLUSALL` | `FAIL` | SPF contains `+all`. |

DKIM mapping:

| Rspamd Symbol | Report Status | Meaning |
|---------------|---------------|---------|
| `R_DKIM_ALLOW` | `PASS` | At least one DKIM signature verified. |
| `R_DKIM_REJECT` | `FAIL` | DKIM signature verification failed. |
| `R_DKIM_TEMPFAIL` | `ERROR` | DKIM verification had a temporary error. |
| `R_DKIM_PERMFAIL` | `FAIL` | DKIM verification had a permanent error. |
| `R_DKIM_NA` | `WARN` | No DKIM signature was present. |

DMARC mapping:

| Rspamd Symbol | Report Status | Meaning |
|---------------|---------------|---------|
| `DMARC_POLICY_ALLOW` | `PASS` | DMARC passed. |
| `DMARC_POLICY_REJECT` | `FAIL` | DMARC failed with reject policy. |
| `DMARC_POLICY_QUARANTINE` | `FAIL` | DMARC failed with quarantine policy. |
| `DMARC_POLICY_SOFTFAIL` | `WARN` | DMARC failed under non-enforcing or sampled policy. |
| `DMARC_NA` | `WARN` | No DMARC policy was found. |
| `DMARC_BAD_POLICY` | `FAIL` | DMARC policy is invalid or duplicated. |
| `DMARC_DNSFAIL` | `ERROR` | DMARC DNS lookup failed. |

The bot also checks DKIM selector DNS for every parsed `DKIM-Signature` header and checks that the header From domain publishes exactly one usable DMARC record with a `p=` policy.

Rspamd symbol references:

- [Rspamd SPF module](https://docs.rspamd.com/modules/spf/)
- [Rspamd DKIM module](https://docs.rspamd.com/modules/dkim/)
- [Rspamd DMARC module](https://docs.rspamd.com/modules/dmarc/)

### MX Receiving Infrastructure

For every envelope/header message domain, the bot resolves MX records and checks each advertised MX target.

Checks:

- MX records exist, or implicit address-record fallback exists where supported by the resolver.
- MX target is not a CNAME.
- MX target is not an IP literal.
- MX target has A/AAAA address records.
- Each MX accepts TCP connections on port 25.
- SMTP STARTTLS and certificate expiry are reported for port 25.
- Each MX is probed with `MAIL FROM:<>`, `RCPT TO:<address>`, `RSET`, and `QUIT`; no message body is sent.

Recipient probes:

- Original sender mailbox for the domain, reported as an operational warning if rejected.
- `postmaster@domain`, reported as `FAIL` if rejected because SMTP requires postmaster support.
- Configured RFC 2142 aliases such as `abuse@domain`, reported as warnings if rejected.

### Sending Host Port Probe

The connecting host may not be a receiving MX. For that reason, sending-host port checks are reported as `INFO`. By default only port 25 is checked. Submission ports `465` and `587`, and mailbox access ports `143` and `993`, are not default checks because they are not required for the observed message to have been delivered.

Deployments can add those ports to `portCheckPorts` for site-specific audits.

### Transport Security

For each message domain, the bot checks:

- MTA-STS TXT discovery and HTTPS policy validity.
- TLSRPT DNS policy.
- DANE/TLSA records for MX hosts.
- STARTTLS and certificate expiry from the SMTP port probe.

MTA-STS and TLSRPT are checked against the domain being analyzed, not an arbitrary configured override. DANE is checked at the MX host TLSA names.

### Message Content And Headers

The bot checks common message headers and list-unsubscribe behavior:

- `From`, `Date`, `Message-ID`, and `Subject` are present.
- List-like messages include `List-Unsubscribe`.
- One-click unsubscribe is reported when `List-Unsubscribe-Post: List-Unsubscribe=One-Click` is present.
- Rspamd score, spam verdict, and highest-impact symbols are included when available.

## Configuration

The email bot reads all behavior from the bot definition map in `cfg/bots.json5`. Important keys:

| Key | Default | Purpose |
|-----|---------|---------|
| `rblCheckEnabled` | `true` | Enable connecting IP DNSBL checks. |
| `rblProviders` | Spamhaus ZEN, SpamCop, Barracuda, SORBS | DNSBL zones to query. |
| `dblCheckEnabled` | `true` | Enable domain reputation checks. |
| `dblProviders` | Spamhaus DBL, SURBL, NordSpam DBL | DBL/SURBL zones to query. |
| `rdnsCheckEnabled` | `true` | Enable EHLO/PTR/FCrDNS analysis. |
| `spfCheckEnabled` | `true` | Interpret Rspamd SPF symbols. |
| `dkimCheckEnabled` | `true` | Interpret Rspamd DKIM symbols and selector DNS. |
| `dmarcCheckEnabled` | `true` | Interpret Rspamd DMARC symbols and DMARC DNS record. |
| `mxCheckEnabled` | `true` | Resolve and test message-domain MX infrastructure. |
| `recipientProbeEnabled` | `true` | Probe sender and role recipients on each MX without sending DATA. |
| `roleAliases` | `postmaster`, `abuse` | Administrative aliases to probe. Other RFC 2142 aliases can be added for broader domain audits. |
| `probeEhloName` | `robin-analysis.local` | EHLO name used by recipient probes. |
| `probeMailFrom` | empty reverse path | MAIL FROM used by recipient probes. |
| `recipientProbeTimeoutSeconds` | `10` | SMTP recipient probe timeout. |
| `portCheckEnabled` | `true` | Enable sending-host port checks. |
| `portCheckPorts` | `[25]` | Ports checked on the observed sending host. |
| `portCheckTimeoutSeconds` | `10` | Port and TLS probe timeout. |
| `mtaStsCheckEnabled` | `true` | Enable MTA-STS and TLSRPT checks. |
| `daneCheckEnabled` | `true` | Enable DANE/TLSA checks. |
| `domainAgeCheckEnabled` | `false` | Enable optional RDAP domain-age lookup. |
| `domainAgeTimeoutSeconds` | `5` | RDAP lookup timeout. |
| `newDomainWarnDays` | `30` | Warn when RDAP registration age is below this threshold. |
| `spamAnalysisEnabled` | `true` | Include Rspamd score and message-content signals. |

## Checks Intentionally Excluded By Default

- Open relay testing: it is intrusive and not required to explain a received message.
- Submission/access ports: `465`, `587`, `143`, and `993` are not delivery requirements, so they are configurable but not defaults.
- General website, HTTP, DNSSEC zone, TLS web, or brand checks: those are outside a message-centric email deliverability report unless a future feature explicitly adds them as optional site audits.
- Domain age as a pass/fail gate: age is reputation context, not proof of good or bad mail.
