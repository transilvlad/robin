# Real-Suite Cyrus IMAP Inventory

Status: prepared for parent review and offline testing. No live legacy or
actual-data clone pass is claimed. This is a separate protocol gate alongside
the [cold snapshot and staged new-volume upgrade guide](cyrus-jmap-upgrade.md).
It does not replace or invoke `upgrade.py`, start services, build images, take
snapshots, grant rights, or authorize maintenance/promotion.

`.suite/cyrus/suite-inventory.py` uses Python's standard library only. Use
Python 3.11+ on Linux, or WSL with private files on a Linux filesystem. Native
Windows is refused because Unix permission bits do not establish private NTFS
ACLs. Do not place the private directory on `/mnt/c` or `/mnt/d`, shared mounts,
or a filesystem that only emulates Unix permissions.

## Operator Preconditions

- Parent selects the exact endpoint, address, stage, and actual Cyrus admin
  principal. Credentials must be for the real suite administrator, not the
  synthetic fixture. The tool cannot establish administrative authority from
  successful LOGIN alone. Confirm that this principal's `LIST "" "*"` covers
  the intended Cyrus namespace; it cannot discover mailboxes hidden from it.
  Admin LIST/GETACL permission need not imply message read permission. Any
  selectable mailbox that this principal cannot EXAMINE/FETCH fails the gate;
  the helper cannot grant the missing rights.
- Reserve a sequential protocol slot. The live legacy scope is a read-only
  pre-maintenance observation. The candidate scope must point at a separately
  reviewed **new-volume clone**. Scope is an operator assertion, not a volume
  inspection: this script has no Docker access or independent volume proof.
- Block unrelated clone writers and user sessions during inventory. Live
  observations before maintenance can race delivery and other clients. Ordinary
  churn is detected and fails; IMAP has no multi-mailbox atomic snapshot. A
  successful scan cannot prove that no concurrent write occurred between its
  observations. Reconcile the baseline with the later cold snapshot, and take a
  fresh controlled baseline if live data changed before that snapshot.
- Supply an explicit hostname and numeric `--connect-address`. The latter pins
  the reviewed network destination without DNS lookup inside the tool. Check
- Select `--connect-address` as the separately reviewed numeric destination
  address. TLS still validates the certificate against `--host`.
  Nothing defaults to a production, legacy, or rehearsal destination.
- Keep evidence and credential files outside Git and outside mail volumes in
  an existing owner-only directory (0700); regular private files must be owned
  by the invoking UID, have no group/other permission bits (normally 0600), and
  have one link. Symlinks, hardlinked input files, and writable ancestors are
  refused, except a root-owned sticky ancestor such as `/tmp`. Use durable,
  protected storage rather than `/tmp` for retained operational evidence.

## Private Credentials

Use `--username cyrus --password-file PRIVATE_RAW_SECRET` with the existing raw
Cyrus admin secret. Parent's configuration inspection identified `admins: cyrus`,
`configdirectory: /var/lib/cyrus/config`, partition `/var/lib/cyrus/mail`,
`altnamespace: yes`, `unixhierarchysep: yes`, and `virtdomains: userid`. This
verifier does not read these server files or the actual secret during preparation.
Its explicit admin LIST pattern is `*`, covering visible other-user namespaces.

The password file must contain the actual raw admin password, at least 32 and
at most 8192 printable ASCII characters, optionally followed by a single LF or
CRLF. Spaces are preserved; short secrets, embedded controls, and multiple
trailing lines fail. The explicit username must be 1-1024 printable ASCII
characters. There is no password argument, environment-secret, interactive
prompt, JSON copy requirement, fallback account, impersonation, ACL grant, or
auth retry. Other authentication mechanisms or non-ASCII credentials require a
separately reviewed implementation.

The parent can mount the existing password file read-only into the isolated
probe, inside an owner-only directory and with owner-only file permissions.
The standard `/run/secrets` directory is often 0755 and secret files can be 0444;
those permissions are intentionally refused. Arrange the mount path and access
mode to meet the guards, for example inside `/secure-local/cyrus-inventory`
owned by the probe UID with mode 0700 and its mounted secret mode 0400/0600.
Do not relax the guards or change live secret permissions to accommodate this
tool. Never put the password in shell arguments, chat, or shared logs.

## Capture And Compare

The following addresses, stage labels, and paths are examples for the parent to
replace with the privately reviewed choices. These commands are instructions,
not evidence that an endpoint or certificate exists. Every manifest path must be
new. Create/protect the private directory and credential file before invoking
the helper; the helper does not create directory trees or relax permissions.

Read-only baseline against the explicitly selected live legacy endpoint, before
maintenance, using verified implicit TLS:

```sh
python3 -B .suite/cyrus/suite-inventory.py capture \
  --host legacy-imap.example.test --connect-address 192.0.2.10 --port 993 \
  --scope legacy-before-maintenance --stage legacy-before \
  --ca-file /secure-local/cyrus-ca.pem \
  --username cyrus --password-file /secure-local/cyrus-inventory/admin-password \
  --manifest /secure-local/cyrus-inventory/legacy-before.json
```

After the parent has independently restored the cold snapshot onto a new volume
and started a reviewed rehearsal stage, capture that stage sequentially:

```sh
python3 -B .suite/cyrus/suite-inventory.py capture \
  --host rehearsal-imap.example.test --connect-address 192.0.2.20 --port 993 \
  --scope new-volume-clone --stage clone-3.8.8 \
  --ca-file /secure-local/cyrus-ca.pem \
  --username cyrus --password-file /secure-local/cyrus-inventory/admin-password \
  --manifest /secure-local/cyrus-inventory/clone-3.8.8.json

python3 -B .suite/cyrus/suite-inventory.py compare \
  --before /secure-local/cyrus-inventory/legacy-before.json \
  --after /secure-local/cyrus-inventory/clone-3.8.8.json
```

Repeat capture and comparison with fresh manifest names after each approved
3.10.4 and 3.12.4 stage, restart, and old-image/new-volume rollback. Compare each
stage against the baseline and, as useful, its predecessor. The `after` manifest
must assert `new-volume-clone`; compare never connects or reads credentials.

Implicit TLS is the default and requires hostname/certificate verification,
system trust or an explicit CA, and TLS 1.2 or newer. There is no insecure-TLS or
STARTTLS mode, and no retry/downgrade on certificate failure. An unpublished
plain IMAP endpoint is allowed **only** with `--plain-private-rehearsal`, scope
`new-volume-clone`, and a numeric destination in IPv4 RFC1918/loopback or IPv6
ULA/loopback. For that explicitly approved exception, choose the appropriate
plain port and omit `--ca-file`. Private IP space alone is not proof of an
isolated network. For a legacy endpoint offering only local plain IMAP, the
separate explicit `--plain-loopback` option permits either scope on IPv4
127.0.0.0/8 or IPv6 ::1 only. Run the probe inside the reviewed endpoint's
network namespace with the protected raw secret mounted read-only; this tool
does not create that environment. Legacy plain access to non-loopback private
addresses is refused. The two plain options are mutually exclusive.

## Inventory And Comparison Contract

- `LIST "" "*"` records every returned raw mailbox name, hierarchy delimiter,
  and LIST attribute, with a second full LIST consistency check. Names use
  lossless hexadecimal octets rather than guessing modified UTF-7/UTF-8. Hex
  is encoding, not anonymization: these names remain private evidence.
- Each selectable mailbox uses **EXAMINE**, never SELECT. Its EXISTS,
  UIDVALIDITY, UIDNEXT, and complete `UID SEARCH ALL` set must agree. Every UID
  receives a metadata preflight, then `UID FETCH` for UID, FLAGS, INTERNALDATE,
  RFC822.SIZE, and **BODY.PEEK[]**. The exact returned bytes must match the
  reported size and are hashed with SHA-256. Oversized mail fails before the
  intended body request; transport checks also reject oversized unsolicited
  literals before allocation.
- Python's structured `email` header parser records only header count and
  defect class names. Legacy header defects are observations, not silently
  repaired mail or automatic failures. Hashing always covers the original full
  raw bytes, including MIME parts, attachments, line endings, and binary octets.
  Neither raw bodies nor address/subject header values are saved in the manifest.
- Re-EXAMINE, UID SEARCH, and another metadata pass must agree inside each
  mailbox scan. Final LIST, LSUB, ACL, and quota observations must also agree.
  A denied/unavailable selectable mailbox, missing FETCH, duplicate UID, missing
  counters, unknown response shape, truncation, or limit violation fails the
  entire capture. No completed manifest is published for a partial inventory.
- Structural `\Noselect` LIST nodes are retained with null state, no messages,
  and explicit `not-selectable` ACL/quota observations. They are never EXAMINEd
  or treated as readable mailboxes. `\NonExistent` entries fail. A scan with no
  selectable mailboxes fails; an otherwise complete empty message store is
  valid and reports zero messages, which proves no nonempty corpus.
- LSUB captures **the logged-in admin principal's subscriptions**, including
  subscribed names not returned by LIST. It does not enumerate individual
  users' subscriptions. GETACL captures identifiers and rights for selectable
  mailboxes only when ACL is advertised. GETQUOTAROOT captures quota roots and
  each resource's usage/limit only when QUOTA is advertised. Missing capability
  means explicit `unsupported`, not successful verification of that feature.
  An advertised extension returning NO/BAD or incomplete data fails. A supported
  mailbox with an explicit empty quota-root response records an empty root map;
  missing data for a named root fails. No ACL/quota writes are attempted.
- Comparison validates the complete manifest schema and counts, then requires
  identical mailbox names/attributes/delimiters, subscriptions, observation
  support states, ACLs, quota roots/resources/usage/limits, UIDVALIDITY, message
  UID sets, flags, INTERNALDATE strings, sizes, hashes, and header observations.
  Set-like flags, ACL rights and quota resources are canonically ordered; flag
  case is normalized. **All flags, including volatile `\Recent`, are compared.**
  A difference is a failed gate that the parent must investigate, even if it has
  a plausible operational explanation. Quota usage changes are not ignored.
- UIDNEXT must also be identical by default. Only the explicit comparison
  option `--allow-uidnext-increase` permits strictly higher UIDNEXT, never a
  decrease or any other difference. The public result includes the number of
  permitted increases. This option does not accept new UIDs or mail loss.
  Host/address/port, stage, and scope describe provenance and are not equality
  invariants; the after-scope restriction still applies. Manifests are trusted
  local evidence, not signed attestations or independently authenticated backups.

## Bounds And Privacy

Defaults and upper limits are deliberate; reaching a limit is failure, never a
partial success. Tune explicitly before the next sequential attempt and use a
new manifest path.

| Option | Default | Maximum |
| --- | --- | --- |
| `--deadline` | 300 seconds | 86400 seconds |
| `--socket-timeout` | 10 seconds | 86400 seconds, also capped by remaining deadline |
| `--max-mailboxes` | 1000 LIST and 1000 LSUB entries | 100000 each |
| `--max-messages` | 10000 across all selectable mailboxes | 1000000 |
| `--max-literal-bytes` | 32 MiB | 128 MiB |
| `--max-wire-bytes` | 512 MiB received across the connection | 2 GiB |
| `--max-output-bytes` | 16 MiB manifest | 16 MiB |

Protocol lines also cap at 64 KiB, with at most 16 nested lists and 200000 parsed
tokens per response. Counts include structural LIST nodes. Wire bytes include
greeting, tagged replies, unsolicited data, metadata and literals. The transport
enforces the deadline and socket timeout on every recv/send and connection step;
numeric address pinning avoids an unbounded DNS call. These are protocol bounds,
not a kill timer for local filesystem stalls, fsync, or CPU work parsing a
bounded message. Bodies are held in memory up to the literal cap, not streamed
to disk. Large, malformed, or unusually extensible stores can fail these bounds
and need an explicit parent review, not silent truncation or fallback parsing.

Successful output is one fixed-shape count line. Failure output is one bounded
reason code, never raw exception text, server diagnostics, mailbox names,
identifiers, hostnames, file contents, subjects, or messages. No verbose IMAP
debug option exists. Do not wrap execution with protocol tracing or shell secret
tracing. An outer Python audit hook or debugger remains outside this tool's
privacy boundary.

The full inventory, including mailbox names, ACL identities, subscription names,
endpoint and message fingerprints, goes **only** to a private manifest. The
writer checks an existing private parent directory, creates a 0600 temporary
file, bounds and fsyncs its contents, atomically publishes with a no-replace
hardlink, removes the temporary name, and fsyncs the directory. Existing output
names, including dangling symlinks, are refused before credential reads or
connection. A publication race cannot overwrite another file. Failure before
publication removes its temporary file; a process crash can leave a private
temporary file. A failure during final directory fsync can leave a completed
manifest even though the command failed: inspect it privately, and never reuse
the output path. Permission checks cannot validate an untrusted mount's actual
access enforcement; the filesystem requirement above is an operator gate.

Exit 0 means this capture/comparison met its stated contract. Exit 1 means stop
and retain private evidence for review. No automatic server mutation, CLOSE,
EXPUNGE, logout command, retry, repair, cleanup of existing evidence, or stage
advance occurs. Socket shutdown only closes this client's connection.

## Offline Validation And Remaining Gates

Parent validation on 2026-10-30 in WSL Ubuntu 24.04: 43 tests ran, 42 passed,
one native-Windows-only test skipped. All six POSIX filesystem cases passed.
Log: `log/cyrus-suite-inventory-units-20261030.log`. This is not a live inventory.

```sh
python3 -B -m unittest discover -s .suite/tests -p test_cyrus_suite_inventory.py -v
```

The tests use synthetic IMAP objects, a fragmenting in-memory socket driving
real imaplib response parsing, temporary fake raw secrets/manifests, and mocked
TLS failures. They do not contact a server, resolve DNS, read suite secrets,
build images, or use Docker. Six POSIX filesystem tests skip on native Windows;
run them on the intended Linux/WSL filesystem before operational use. Native
Windows instead tests the fail-closed private-file refusal.

Parent must still review and run the actual suite-data baseline/clone comparisons
sequentially after the webmail HTTP gates and appropriate maintenance/network
authorization. This helper cannot prove per-user subscription ownership, user
authorization/isolation, Sieve rules/execution, annotations, conversations/search
indexes, delivery, outbound identity/alias rules, JMAP access, or snapshot/volume
provenance. Those remain separate gates in the upgrade guide.
