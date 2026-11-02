# Isolated local mail release gates

`.suite/tests/local_release.py` runs bounded acceptance against immutable existing
Robin, PostgreSQL and patched Cyrus images. It does not build, pull, promote,
restart live services, modify production accounts or delete volumes. Obtain the
exclusive Docker execution slot first on a shared host.

The source is a completed, stopped actual-data Cyrus rehearsal volume, never
`suite-cyrus-data`. It is mounted read-only and copied into a new project. The
gate disables expiration/reindexing, preserves original raw message hashes and
adds only synthetic `example.test` users in an independent PostgreSQL database.
An operator-private copy of the exact Robin runtime configuration is required;
only isolated authentication/database, TLS, routing/worker and policy settings
are overridden. The unfinished checked-in/live Compose chain is never resolved.
Authentication uses Robin's real SQL verifier and Cyrus's real Robin/PAM path.
Every fixture network is internal and no host ports are published. The bounded
protocol client runs with Cyrus's existing Python interpreter inside the owned
container and verifies that it has no IPv4 default route. MX workers have a
one-day startup delay, native Cyrus submission
remains `/bin/false`, and no public mail route or original account is used.
MX routing is disabled. Two extra accepted synthetic inbound messages are queued
to an unavailable fixed **internal** relay endpoint solely to exercise real queue
records/payloads; they are not replayed or sent externally.

The SMTP gate requires TLS submission and SMTPS authentication and expects rejection of missing/
invalid authentication, unauthorized envelope/From/Sender/duplicate From,
receive-only aliases, revoked grants and unavailable authorization tables. It
confirms two accepted primary/explicit-alias messages reached the canonical local
recipient and no rejected message reached that sink. Sender authorization is
explicitly enabled **only in the isolated fixture**; passing does not prove the
live database/configuration has that policy enabled.
Failed policy responses are recorded as failures, while independent Sieve and
recovery checks continue; the overall process still exits nonzero.
The policy query uses `SharedDataSource` and therefore the Dovecot `authSql`
database role, not necessarily the MTA/config-store role. The permission-outage
fault revokes policy-table SELECT from both fixture roles while preserving
password-query access, so real authentication must still succeed before MAIL
is temporarily rejected.

ManageSieve authenticates, uploads and activates a fileinto script. Actual LMTP
delivery must file exactly one matching message without an Inbox duplicate and
implicitly keep a nonmatching message. Plaintext IMAP/ManageSieve/LMTP endpoints
are confined to the isolated internal fixture and use synthetic secrets.
This is execution acceptance, not just a capability or script-upload check.

The recovery gate calls the real `.suite/recovery.py` cold backup/restore helpers
for the same isolated project. Its snapshot includes physical PostgreSQL, real
Robin serialized pending queue rows, durable payload files, Cyrus data,
configuration, generated secrets and actual runtime images. Restore uses a new
project with empty data volumes, exact old images and no automatic queue replay.
The harness audits the flattened resolved Compose mount set, every mounted input
archive/checksum and exact runtime images before restore. The general helper's
`deployment.tar` alone is not a complete source-input backup: fixture TLS,
initialization scripts and Cyrus configuration also live in the mount archives.
Supplemental resolved Compose, input audit, runtime-input hashes and harness
sources are retained inside encrypted evidence.
Complete rows for seven tables and all payload hashes/numeric ownership/modes
must match; PostgreSQL, Robin and Cyrus must start successfully afterward.
This is a coherent copy rehearsal, **not a backup of the current live suite or
its independently deployed Admin/Webmail databases**. Live recovery still needs
the full maintenance procedure in [suite operations](../developer/suite-operations.md).

Run on WSL/Linux with Python 3.11+, Docker Compose, Git, OpenSSL and GnuPG.
Use a new owner-private Linux home directory and an owner-only key file; do not
use NTFS for plaintext evidence. Pass all four local `sha256:` image IDs via
`--robin-image`, `--postgres-image`, `--cyrus-image` and `--helper-image`, together
with `--source-cyrus-volume`, `--runtime-config`, `--root`, `--key-file` and
`--encrypted-evidence`.
The source revision is read from the current checkout without creating a commit.
The harness copies that actual commit object into owner-private detached bare
metadata (no tree/source checkout) and binds the real Git revision check to it.
Concurrent primary commits cannot turn a coherent fixture restore into a false
revision mismatch. This records frozen inputs/helper provenance, not a claim
that the evolving checkout is identical to the deployed runtime artifact.
Protect the backup key independently (for example CurrentUser DPAPI with a
reviewed private Windows ACL). The output archive is AES256-encrypted, decrypted
again for byte-exact verification and accompanied by a SHA256 sidecar. Protected
working evidence and every original/restored/failed volume are retained.
Encryption/decryption each have a ten-minute bound. Compression is disabled for
the large, already-compressed image/JAR inputs. Existing ciphertext and archive
artifacts are never overwritten; an incomplete generation needs a separate
destination, and encryption errors are retained privately.
Inspect `result.json` inside protected evidence; only aggregate counts and image/
project identities may be copied to public documentation. Never copy private
rows, mailbox inventories, passwords or keys into chat or the repository.
Protocol/recovery acceptance does not clear image-package vulnerability or
production-exposure review; those remain independent release prerequisites.

Offline checks, requiring no containers:

```text
python3 -m unittest discover -s .suite/tests -p test_local_release.py
python3 -m unittest discover -s .suite/tests -p test_recovery.py
```
