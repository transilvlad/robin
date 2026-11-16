# End-to-end test plan

Status: steps 1-3 implemented 2026-11-14 (Phase 0 green in Docker; CI workflows added;
harness with robin-sink, http-sink, test DNS and folder discovery). Step 4 (the P0 cases of
sections A-E, except HTTP auth, which needs `robin-edge`) implemented 2026-11-15. Later steps proposed.
The harness found two release bugs on its first relay: the shipped `cfg/server.json5` pointed
the truststore at a JDK path missing from the Corretto image, which disabled MTA-STS, and
`StrictMx` then threw a NullPointerException that bypassed the 451 retry handling, so every
MX delivery failed. `robin-edge` is deferred to the steps that need it (policy, auth, DoS). Goal: release confidence for production use, and a
suite of JSON5 cases that doubles as a demonstration of what Robin's case system
can test on an MTA.

**Bugs step 4 found and fixed**

- BDAT kept only the last chunk of a multi-chunk message, and applied the size limit per chunk.
- Dot-stuffing: the client stuffed only lines that were exactly `.`, and the server never
  removed the stuffed dot, so stored messages, scanners, webhooks and DKIM saw stuffed content.
- AUTH was offered on the submission port before STARTTLS, and STARTTLS was offered again
  after TLS was up (RFC 3207 4.2).
- The MIME builder wrote `Message-ID: <uuid>`: invalid (no domain, scored by Rspamd) and not
  the envelope's ID, so IMAP assertions could never find a built message.
- DSNs did not reference the original message: they now carry `In-Reply-To`/`References`
  and the original headers as a `text/rfc822-headers` part.
- `/client/queue` refused cases using `subject`/`message` or `mime` with `507 Insufficient
  Storage`; it now builds the message before queueing and answers 400 when there is none.
- Request `content.payload` was the only request field without magic replacement.
- Harness: the per-IP connection cap (10) dropped concurrent cases; `example.com` had no test
  zone, so DSNs to local senders went to the real domain's null MX.

**Open questions from step 4**

- 8BITMIME is not advertised although SMTPUTF8 is (RFC 6531 requires 8BITMIME support).
- `/client/queue/bounce` marks the item dead without sending the sender a DSN, and `markDead`
  is unconditional: it also kills an item a worker is delivering.
- Queue worker log lines carry the previous item's session, IP and sender in the context columns.
- RSET is a no-op on the client connection, and the server enforces no command sequence
  (BDAT or DATA without MAIL).
- `/logs` logs its own query, so `verifyNone` on `/logs` always matches itself.
- The seeded auto-alias `tony@` is not resolved by the integration alias query.
- No spam or `Authentication-Results` headers are added on clean mail (Rspamd returns no
  header mutations in this stack), so the section C spam-header row is not covered.

## 1. Where we are

**What runs today**

- The only Robin end-to-end suite is `.integration`, with 11 JSON5 cases
  (`src/test/resources/cases/config/integration/01-10, 08b`) driven by
  `cases.IntegrationTest`.
- The stack is one Robin, Postgres, Redis, ClamAV, Rspamd and Dovecot.
- CI (`.github/workflows/maven.yml`) runs unit tests only. `.integration`,
  `.suite` and `.perf` are all manual. `docker-publish.yml` publishes images on
  tags without running any tests.

**What the existing cases actually prove** (verified in code)

| Area | Status |
|---|---|
| SMTP reply codes (MAIL/RCPT/DATA) | Real |
| IMAP header checks (02, 03, 06) | Real |
| `/logs` `request` assertions | **No-op.** When `verify` does not match and `verifyFails: true`, `RequestExternalClient.run()` neither throws nor skips (`RequestExternalClient.java:136-150`). Verify misses are also never retried. |
| 06 log verify | Wants two strings on the *same line*. Passes only because of the bug above. |
| 09 chaos LDA | Looks for "Queuing email for relay", which does not exist in `src/main`. The header lacks the required `recipient=`. LDA is disabled in that stack. The case tests normal delivery. |
| `["QUIT"]` in every case | Ignored: protocol entries whose size is not 2 are skipped silently (`Assert.java:94`). |
| `["RCPT","^250"]` written twice (03) | Proves one recipient, not two: "any transaction matches". |

**Never exercised end to end**

- relay/MX, queue/retry, bounce/DSN, DKIM signing
- AUTH/submission (587/465), open-relay rejection
- BDAT, SMTPUTF8, size limit
- webhooks, proxy, bots, block/allow/blackhole, DoS limits
- every HTTP endpoint except `/logs`
- HTTP auth, metrics/health

**Case features no running test uses**

- `lhlo`, `auth` (all variants), `xclient`, `behaviour`, `params`
- multiple envelopes / `repeat` / RSET
- `mime` builder, `chunkSize`/`chunkBdat`/`chunkWrite`
- `terminate*`/`slow*`, `$` magic map, `condition`
- IMAP `parts`/`body`/`hashes`/`delete`/`purge`
- `logs` external, `match`/`refuse`/`magic` captures, `verifyNone`
- HTTP request cases with assertions

## 2. Principles

1. **A test that cannot fail is worse than no test.** Fix the assertion engine
   before adding cases, and add *self-test* cases that must fail.
2. **Robin tests Robin.** A second Robin acts as a scriptable remote MTA
   (`scenarios.json5`) and HTTP sink. This covers outbound paths without
   external dependencies and shows off both halves of the product.
3. **One case = one JSON5 file = one documented capability.** Discover cases by
   folder, so adding coverage never needs Java.
4. **Assert the outcome, not just the reply code.** Check mailbox content with
   byte hashes, queue state, the remote MTA's received copy, and webhook
   payloads.
5. **Gate releases on it.** No image is published without the full suite
   passing against that exact image.

## 3. Phase 0: make the framework trustworthy (prerequisite)

These are framework fixes. They also matter to every external user of Robin's
case system.

### Assertion engine

| # | Change | Where |
|---|---|---|
| 0.1 | `request` external: a verify miss throws when `verifyFails`, and verify misses are retried within `retry`/`delay` | `RequestExternalClient.run/makeRequest` |
| 0.2 | Real absence assertions: `verifyNone` fails if data matches (logs, request). Add an IMAP `absent: true` mode (message must *not* arrive within wait/retry). | `LogsExternalClient:88`, `RequestExternalClient`, `ImapExternalClient` |
| 0.3 | Reject malformed protocol entries at load time instead of ignoring them | `Assert.java:94` |
| 0.4 | Protocol assertions for repeated transactions: optional index or `all`, e.g. `["RCPT", "^250", "all"]` / `["RCPT", "^550", 1]`, plus a negated form | `Assert.assertProtocol` |
| 0.5 | `match`/`refuse` groups match on the same line, as documented. IMAP header patterns are anchored to the header name. | `MatchExternalClient:191-207`, `ImapExternalClient` |
| 0.6 | HTTP status-code assertion. Store the status in the `HTTP` transaction (`["HTTP", "^200"]`). Add PATCH and HEAD. | `RequestClient`, `HttpResponse` |
| 0.7 | IMAP header/part mismatch retries like a not-found message | `ImapExternalClient:189-207` |
| 0.8 | Smaller fixes | see list below |

Smaller fixes for 0.8:

- `magic` captures may overwrite existing keys.
- `refuse`/`magic` patterns are magic-replaced.
- Logs `grep -v` is fixed.
- `ConfigMapper.magicReplace` stops blanking values that start with `{$`.
- `ClientEhlo` uses a literal `replace("^auth")`.
- `ClientHelp` expects 214.
- `transactionResponse` is set for BDAT.
- A session-level IMAP assertion no longer crashes.

### Schema and validation

- Complete `case.schema.json`.
  - Missing keys: `$`, `behaviour`, `xclient`, `helo`, `lhlo`, `assertions`,
    `params`, `headers`, `prependHeaders`, `folder`.
  - Wrong defaults: `tls`, `chunkSize`, `timeout`.
  - Address regexes that reject valid input.
- Fail loudly on an envelope with no message source, which is currently dropped
  silently (`ConfigMapper.java:222`).
- New unit test `CaseFilesSchemaTest`: every `*.json5` under
  `src/test/resources/cases` and `examples/` validates against the schema. This
  runs in CI and stops case and doc drift.

### Repair existing cases

- 06: split the verify into two lines.
- 09: rewrite so it really forces the queue path, using a chaos target that
  applies to LMTP inline. If none exists, add a `DovecotStorageProcessor`/LMTP
  chaos option. Assert on the queue API, not a log string.
- `["QUIT"]` becomes `["QUIT","^221"]`.
- 03 uses `all`.

### Self-tests (`integration/selftest/`)

Cases that **must** fail. A `@TestFactory` asserts that `Client.send` throws
`AssertException` for each:

- wrong reply code
- wrong IMAP subject
- wrong log string
- an absent message expected present
- a present message expected absent
- wrong part hash
- wrong HTTP status

If any of these starts passing, the framework has regressed.

## 4. Phase 1: harness

### Stack additions to `.integration/docker-compose.yaml`

| Service | Role |
|---|---|
| `robin-sink` | Second Robin as the "remote internet MTA". It loads `scenarios.json5` (tempfail-, permfail- and greylist addresses), with local storage plus `/store` API enabled. Used as the relay/MX target and for checking the received copy (headers, DKIM-Signature, DSN). |
| `robin-edge` (optional profile) | Robin under test with a stricter config: HTTP Basic/Bearer auth on, blocklist/blackhole, webhooks, proxy, bots, tight DoS limits. Keeps the main Robin's config close to production defaults. |
| `http-sink` | Tiny recorder (a Python `http.server` script, ~60 lines, in the test image) that stores requests and serves them back as JSON. It can be scripted to return a given status or body. Used for webhooks, bot endpoints, and `/client/send` callbacks. |
| `dns` (CoreDNS) | Test zones: MX for `remote.test` points to `robin-sink`, RBL zone, MTA-STS/TLSA records later. Robin under test uses it as its resolver. |

Robin's container health check should become `GET /health` instead of
`nc -z 25`.

### Test runner

- Replace the per-method list in `IntegrationTest` with a `@TestFactory` that
  discovers `cases/config/integration/<area>/*.json5`, tagged by folder.
  Keep explicit methods only where Java orchestration is needed (container
  restart, parallel clients).
- Folder layout:

  ```
  inbound/ submission/ delivery/ scanning/ queue/ relay/ api/
  policy/ webhooks/ proxy/ bots/ dos/ selftest/
  ```

- Each case starts with the existing comment header: Test / Flow / Validates.
  `doc/testing/integration.md` is generated or maintained as a case-to-capability
  index (the "demonstration" view).
- Isolation: each case uses unique recipients (`{$RANDCH[8]}`-style local parts,
  via the `$` map) or IMAP `delete: true`. Ordering does not matter and reruns
  are clean.

### CI

| Workflow | Trigger | Runs |
|---|---|---|
| `integration.yml` | PR (label `e2e` or paths `src/main/**`), nightly | Builds the image once, runs the full suite, uploads surefire reports and Robin logs as artifacts. Caches the ClamAV signature volume. Includes a `dos/` job. |
| `docker-publish.yml` | tag `v*` | `needs: integration` against **the image being published** (build → test → push the same digest) |

## 5. Phase 2: new cases

Priority: **P0** blocks release confidence, **P1** important, **P2**
nice-to-have or depends on framework additions. "Shows" lists the case-system
features each case demonstrates.

### A. Inbound protocol (`inbound/`)

| P | Case | Asserts | Shows |
|---|---|---|---|
| P0 | EHLO capabilities | PIPELINING, CHUNKING, SMTPUTF8, 8BITMIME, STARTTLS present; AUTH absent before TLS, present after (`SHLO`) | protocol, `SHLO` |
| P0 | TLS negotiated | `TLS` transaction is TLSv1.3/1.2; `protocols:["TLSv1"]` fails the handshake | `protocols`, `ciphers` |
| P0 | BDAT byte-exact | `chunkSize` 4096, plus `chunkBdat` and `chunkWrite` variants; IMAP part `hashes` equal source | chunking, IMAP hashes |
| P0 | DATA transparency | dot-stuffed lines, mixed EOL (`lipsum.mixed.eol.eml`), binary attachment; hash-exact delivery | IMAP parts/hashes |
| P0 | Size limit | `mime` + `appendRandomBytes` over `emailSizeLimit` gives 552 on DATA **and** on BDAT; nothing delivered | mime builder, IMAP absent |
| P0 | Multiple envelopes per connection | 3 envelopes, with and without `rsetBetweenEnvelopes`, plus `repeat: 5`; each one delivered | envelopes, repeat |
| P1 | SMTPUTF8 | UTF-8 local part and subject delivered, header intact | UTF-8 addresses |
| P1 | Null sender | `mail: ""` accepted and delivered | null reverse-path |
| P1 | Aborted transfer | `terminateBeforeDot`, `terminateAfterBytes`: no delivery, no queue entry | IMAP absent, logs |
| P1 | Slow client inside limits | `slowBytes`/`slowWait` still delivered | slow writes |
| P1 | Out-of-sequence commands | `behaviour: [EHLO, RCPT, …]` gives 503; `envelopesLimit` enforced | behaviour |
| P1 | HELO and LHLO on the SMTP port | `helo`, `lhlo` (LMTP per-recipient `LMTP` transactions) | helo/lhlo |
| P2 | Implicit TLS 465, PIPELINING | **needs client support** (see section 6) | |

### B. Submission and auth (`submission/`, port 587)

| P | Case | Asserts |
|---|---|---|
| P0 | AUTH PLAIN, AUTH LOGIN (`authLoginCombined`, `authLoginRetry`) success, delivered | 235 |
| P0 | Bad password gives 535; MAIL without AUTH gives 530 | |
| P0 | `authBeforeTls` refused (AUTH not offered in cleartext) | |
| P0 | **Open relay:** unauthenticated port 25 to `remote.test` gives 5xx, and nothing reaches `robin-sink` | |
| P0 | Authenticated relay to `remote.test` arrives at `robin-sink` | |
| P1 | Sender authorization: authed `tony` with `MAIL FROM:<pepper>` gives 550, and a forged `From:` header too | |
| P1 | XCLIENT from an allowed IP changes the client address (seen in logs); XCLIENT from a non-allowed IP is refused | |

### C. Delivery pipeline (`delivery/`, `scanning/`)

| P | Case | Asserts |
|---|---|---|
| P0 | Every recipient verified | 03 with one IMAP external per recipient |
| P0 | Rich MIME round trip | `mime` builder: text + html alternative + PDF + JPG; IMAP `parts` headers + `hashes` per part |
| P0 | SQL aliases / auto-aliases | delivered to the destination mailbox |
| P0 | Spam reject | GTUBE gives an exact code and text; spam-score headers / `Authentication-Results` present on clean mail |
| P0 | AV | EICAR in nested multipart, base64, and zip: rejected. Clean PDF/JPG not rejected (false-positive guard). |
| P0 | Chaos matrix | each storage processor `return=false` gives the documented code (451 vs queued) and IMAP absent |
| P1 | Dovecot over quota for one of two recipients | partial outcome correct |
| P1 | Envelope `headers` + `prependHeaders` | headers present in the delivered copy |

### D. Queue, relay, retry, bounce (`queue/`, `relay/`): biggest gap

Config:

- relay enabled
- MX via `dns` pointing to `robin-sink`
- retry `firstWaitMinutes` at test scale; seconds if supported, otherwise add a
  test-only override

| P | Case | Asserts |
|---|---|---|
| P0 | Outbound relay | message to `user@remote.test` arrives at `robin-sink` with correct envelope and headers (`request` on sink `/store` or `/logs`) |
| P0 | Temp fail then queue | `tempfail@remote.test` → appears in `/client/queue/list`; sink logs show ≥2 attempts after the retry interval |
| P0 | Perm fail then bounce | `permfail@remote.test` → DSN in `tony`'s IMAP INBOX: `multipart/report`, `message/delivery-status`, `Status: 5.1.1`, original Message-ID; queue empty |
| P0 | Retries exhausted then bounce | `totalRetries` small; DSN `Action: failed` |
| P0 | Queue API | `/client/queue` (JSON case and raw EML), `list`, `retry`, `delete`, `bounce`, each with an observable effect (listed/absent, delivered, DSN) |
| P1 | Durability | message queued, Robin container restarted, still delivered (orchestrated test; needs docker socket or a CI step) |
| P1 | DKIM signing | relay copy at sink carries a `DKIM-Signature` that verifies (sink Rspamd or an offline verifier); `requireDkimSigning` with no key fails closed |
| P1 | Mixed recipients | 1 ok + 1 tempfail + 1 permfail: one delivered, one queued, one DSN |
| P2 | MTA-STS enforce / DANE mismatch deferred; IP pool selection | |

### E. HTTP APIs (`api/`)

Uses HTTP `request` cases plus status assertions from Phase 0.

| P | Endpoint | Case |
|---|---|---|
| P0 | `POST /client/send` | Robin's API sends to Robin; IMAP confirms delivery. JSON case and raw EML variants. |
| P0 | `/health` (both ports) | 200 and `UP`; unauthenticated response omits internals |
| P0 | **HTTP auth** (`robin-edge`) | no creds 401, wrong Basic/Bearer 401, right 200, allowList bypass. Covers `/client/send`, `/config`, `/system/env`, `/system/heapdump`. |
| P0 | `/users`, `/users/{u}/exists`, `/users/authenticate` | good and bad password |
| P1 | `/store/...` on sink | list, fetch .eml/JSON; folder create, rename, copy, move, delete; read-status, mark-all-read; drafts + attachments round trip; `..` traversal rejected |
| P1 | `/metrics/prometheus` | SMTP counters present and non-zero after a send (later: before/after delta, section 6) |
| P1 | `/config`, `/config/status`, `/config/reload` | reload picks up a changed scenario |
| P1 | `/logs` | query by UID returns that session only |
| P2 | `/store-rocksdb/...` | RocksDB profile |
| P2 | `/mail/proxy-auth` | nginx auth_http headers |

### F. Policy (`policy/`, `robin-edge`)

The client IP is driven via XCLIENT.

| P | Case |
|---|---|
| P1 | Blocklist IP/CIDR gives 5xx at connect/EHLO |
| P1 | Whitelist bypasses the RBL |
| P1 | Blackhole: 250 but IMAP absent and no queue entry |
| P1 | RBL listed via `dns` zone gives reject |
| P1 | Inbound scenarios: per-verb custom responses |
| P2 | GeoIP |

### G. Webhooks (`webhooks/`, `http-sink`)

| P | Case |
|---|---|
| P1 | Every verb fires once; payload has session UID, envelope, and IP. `raw` webhook receives the full message. |
| P1 | Webhook returns a 5xx `smtpResponse`: the SMTP command is rejected with that text |
| P1 | Webhook returns a 2xx `smtpResponse`: **exactly one** reply. This currently fails: `EmailReceipt.java:407-412` writes the webhook reply and continues. Write the test, then fix. |
| P1 | Webhook down or timeout gives 451 (fail-closed) |

### H. Proxy (`proxy/`)

| P | Case |
|---|---|
| P1 | Matching recipient forwarded to `robin-sink` (SMTP and LMTP); multiple envelopes reuse the connection |
| P1 | Non-matching recipient rejected or accepted according to config |

### I. Bots (`bots/`)

| P | Case |
|---|---|
| P1 | SessionBot / EmailAnalysisBot address: auto-reply delivered to the sender with analysis content |
| P1 | No backscatter: no reply to null sender or `Auto-Submitted` |
| P2 | DMARC / TLSRPT report ingestion (fixtures in `mime/dmarc`) posts to `http-sink` |

### J. DoS (`dos/`, separate job, `robin-edge`)

| P | Case |
|---|---|
| P2 | Per-IP connection cap (parallel `Client`s from a Java method) gives 421 |
| P2 | Command flood limit |
| P2 | `slowBytes` below the minimum data rate leads to disconnect |
| P2 | Error-count limit |
| P2 | Tarpit delay measured |

## 6. Client and framework additions

These additions unlock cases and are useful to Robin's users.

| P | Feature |
|---|---|
| P1 | Implicit TLS client (`smtps: true`), for 465 |
| P1 | AUTH mechanism selection (`authMechanism: "PLAIN"`); today the order is fixed |
| P1 | First-class DSN params (`notify`, `ret`, `envid`) instead of raw `params` |
| P1 | `behaviour` verbs: RSET, BDAT, HELO/LHLO, EHLO-after-STARTTLS, multi-envelope |
| P2 | PIPELINING client mode |
| P2 | Numeric comparisons in `request` assertions (`gt` against a `magic` capture), for metrics deltas and queue counts |
| P2 | IMAP STARTTLS |

## 7. Order of work

| Step | Scope | Result |
|---|---|---|
| 1 | Phase 0 (engine fixes, schema test, repair 06/09/03, self-tests) | Existing 11 cases become real tests |
| 2 | CI `integration.yml` + gate `docker-publish.yml` | Every release is tested |
| 3 | Harness: `robin-sink`, `http-sink`, `dns`, folder discovery | Outbound paths become testable |
| 4 | P0 cases: A, B, C, D, E | Release-critical coverage (~35 cases) |
| 5 | P1 cases + client additions | Feature coverage and demonstration value |
| 6 | P2 (DoS, MTA-STS/DANE, RocksDB, GeoIP) | |

Each step lands separately and is green before the next one starts.
