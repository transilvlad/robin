# Cyrus JMAP Suite Upgrade

Status: synthetic clone upgrade/rollback gate passed 2026-10-28; not promoted.
The default suite continues
using its existing Cyrus image/configuration. Webmail remains on Robin API.
Do not publish a new endpoint or rebuild the running mailbox service merely to
try these artifacts. Obtain a Docker/maintenance slot first.

## Version And Safety Gates

The currently inspected suite uses Debian `3.6.1-4+deb12u5`, UID 101 / mail GID 8,
with 52 MiB in named volume `suite-cyrus-data`. This is a local observation,
not a general default or sizing claim. The new image preserves UID/GID and the
existing `/var/lib/cyrus/{config,mail,sieve}` layout. It adds Xapian under `search`.

Upstream recommends reaching 3.10.1 before 3.12, and 3.6.3 or 3.8.1 before 3.10.
Consequently the rehearsal sequence from this suite is **3.6.1 -> 3.8.8 -> 3.10.4
-> 3.12.4**, not a direct binary swap. Intermediate versions run only in the
unpublished rehearsal. Verify a running stage before advancing; investigate any
warnings rather than treating a completed process as an upgrade proof.

The reviewed upstream archive SHA-256 values, checked against official GitHub
release asset metadata on 2026-10-27, are:

| Version | SHA-256 |
| --- | --- |
| 3.8.8 | `13d10babf2e8bb753c5b8fe37328f6f9d43527539957561b840ebfa24a518b61` |
| 3.10.4 | `7ec307f26c7cf12cc59a2c9b59bef4186e51e0c31d933180558289f5ca837860` |
| 3.12.4 | `87a39e27208d9019f00608483d532eb05bd0d71394a6f3f8bb05a72833ad8d91` |

`.suite/cyrus/Dockerfile.jmap` permits only those version/hash pairs and builds
with two jobs, stack protection, FORTIFY and full RELRO. The tarball is pinned;
Debian base/apt inputs are not a reproducible distro snapshot. Preserve built
image IDs and package inventories, scan the resulting images, and review updates
before production. A version pin is not a perpetual security guarantee.

## Authentication And Submission

The PAM helper continues calling Robin's canonical-user authentication API.
HTTP is explicitly added to its IMAP/Sieve service configuration. Canonical
addresses work only when Robin permits that user/password; there is no new
administrative mailbox impersonation. JMAP alias login is **not implemented**;
log in with the canonical address. IMAP alias login remains handled by the
existing nginx mail proxy. No database/flattened configuration is changed here.

**Cyrus JMAP Submission is deliberately blocked** with `sendmail: /bin/false`.
It can still advertise Submission capabilities; capability advertisement is not
a configured outbound route. Configure Webmail's explicit authenticated SMTP
route for sending and verify its own-domain/alias authorization separately.
There is no automatic SMTP fallback after an uncertain JMAP send.

The fixture proved Cyrus does not enforce requested Identity IDs itself. Do not
replace the blocked transport with a shared account or IP-trusted Robin relay.
Native submission remains a separate gate requiring a fail-closed relay that
binds Cyrus's original SMTP AUTH author to both envelope and parsed From header,
including Robin-authorized aliases. Alias sending must not be claimed from an
Identity/get response alone. This limitation must remain visible in Webmail UI.

## Cold Backup

Run on the Linux Docker host, or WSL with paths under `/mnt/d/work/robin`.
The helper requires Python 3.11+ and existing local images. It does not start,
stop, restart or delete services/volumes for you.

1. Record actual suite Compose inputs and running image IDs privately. Retain the
   original admin secret in protected backup storage; this helper does not copy
   secrets. Restrict snapshot storage (0700 directory/0600 files), encrypt it at
   rest and keep it outside the repository, bind mounts and source mail volume.
   In WSL, use a Linux filesystem for Unix mode enforcement, or separately review
   the NTFS/Windows ACLs; `umask` alone does not secure a Windows bind mount.
2. Enter maintenance and block external submission/logins. Drain active delivery
   and stop Robin, IMAP/JMAP proxies, Roundcube, Webmail and its worker; then stop
   Cyrus gracefully (120-second grace). Do not use kill/OOM/crash as quiescing.
   Containers remain stopped until an explicit operator restart.
3. For a whole-suite rollback, first use `.suite/recovery.py` as documented in
   the suite recovery guide. Its cold PostgreSQL + queue + payload backup is
   broader than this Cyrus-only snapshot. Do not restore just the mailboxes
   against an independently rewound or still-processing mail queue/database.
4. Take the Cyrus-specific snapshot:

```sh
python3 .suite/cyrus/upgrade.py snapshot \
  --container suite-cyrus --destination /secure-backups/cyrus-before-jmap
```

It refuses running/crashed Cyrus, listed application writers and other running
containers with a writable mount of the volume. It archives the whole data volume,
old configs and exact old image, preserves hardlinks/ownership, hashes every file,
and writes `COMPLETE` last. A failed snapshot is not usable. No automatic restart.
Checksums detect corruption, not malicious replacement: use only trusted backups.

## Build And Clone Rehearsal

For the automated **synthetic-data** gate, first build all three images below,
then run the following from the repository root in the exclusive Docker slot:

```sh
export CYRUS_LEGACY_IMAGE="$(docker inspect suite-cyrus --format '{{.Image}}')"
export JMAP_NGINX_IMAGE="$(docker image inspect nginx:alpine --format '{{.Id}}')"
bash .suite/cyrus/run-upgrade-rehearsal.sh
```

This reads the live container's image ID only. It creates unique
`robin-cyrus-legacy-*` / `robin-cyrus-candidate-*` projects with a private fake
Robin authentication directory, two synthetic canonical users and a disabled
mailbox. No real Robin DB, admin secret or live mail volume is mounted. It seeds
LMTP deliveries/attachments/folders/flags, takes a cold snapshot, verifies each
staged server and original UIDVALIDITY/UID/flags/date/raw inventory, tests the
final TLS/proxy/auth/size/rate boundaries and restart, then restores the old image
and pre-upgrade data into a fresh rollback volume and repeats the inventory.

The runner stops/removes only its own containers/networks on exit. Its exact new
volume names and a private `/tmp/robin-cyrus-rehearsal-*` evidence directory are
reported and retained for parent review; there is no volume prune. This synthetic
gate does **not** replace the actual suite-data clone rehearsal and maintenance
approval below. Preserve trusted production backups durably, not in `/tmp`.

Build the three allowlisted images **sequentially**, from the Robin root, once
the shared Docker slot is available. Do not use the legacy tag `:local`:

```sh
docker build -f .suite/cyrus/Dockerfile.jmap -t robin-suite-cyrus:3.8.8-jmap \
  --build-arg CYRUS_VERSION=3.8.8 \
  --build-arg CYRUS_SHA256=13d10babf2e8bb753c5b8fe37328f6f9d43527539957561b840ebfa24a518b61 .suite/cyrus
docker build -f .suite/cyrus/Dockerfile.jmap -t robin-suite-cyrus:3.10.4-jmap \
  --build-arg CYRUS_VERSION=3.10.4 \
  --build-arg CYRUS_SHA256=7ec307f26c7cf12cc59a2c9b59bef4186e51e0c31d933180558289f5ca837860 .suite/cyrus
docker build -f .suite/cyrus/Dockerfile.jmap -t robin-suite-cyrus:3.12.4-jmap .suite/cyrus

python3 .suite/cyrus/upgrade.py restore --snapshot /secure-backups/cyrus-before-jmap \
  --volume robin-cyrus-rehearsal-20261027
python3 .suite/cyrus/upgrade.py stage --snapshot /secure-backups/cyrus-before-jmap \
  --volume robin-cyrus-rehearsal-20261027 --image robin-suite-cyrus:3.8.8-jmap
```

Restore always creates a **new** labelled volume. Existing names, even empty
volumes, and the original source volume are refused. Offline stage runs are
networkless, 768 MiB/two CPUs, bound to the recorded image ID. They recover the
database, update indexes and quota; final stage builds conversations and search.
Do not proceed after a failed stage: retain its clone for diagnosis and restore
a fresh clone under another name.

Use `.suite/cyrus/rehearsal.yaml` with a unique project name. It has no published
ports, no restart policy and no `mailbox-backend`/`lmtp-backend` aliases. Only
Robin authentication uses the existing suite network; mail stays on the clone.
It expects these explicit local environment settings:

```sh
export CYRUS_SNAPSHOT_DIR=/secure-backups/cyrus-before-jmap
export CYRUS_REHEARSAL_VOLUME=robin-cyrus-rehearsal-20261027
export CYRUS_JMAP_IMAGE=robin-suite-cyrus:3.8.8-jmap
export JMAP_NGINX_IMAGE='<reviewed nginx image ID or digest>'
export JMAP_SERVER_NAME=jmap-rehearsal.test
export JMAP_TLS_DIR=/secure-local/cyrus-jmap-tls
```

The TLS directory must contain `fullchain.pem` and `privkey.pem` valid for
`jmap-rehearsal.test`; use a dedicated local CA for rehearsal, never disable TLS
verification. Keep its key and any probe credentials file out of Git and logs.

```sh
docker compose -p robin-cyrus-rehearsal-20261027 -f .suite/cyrus/rehearsal.yaml up -d --wait
python3 .suite/cyrus/upgrade.py verify-stage --snapshot "$CYRUS_SNAPSHOT_DIR" \
  --volume "$CYRUS_REHEARSAL_VOLUME" --container '<rehearsal Cyrus container name>'
docker compose -p robin-cyrus-rehearsal-20261027 -f .suite/cyrus/rehearsal.yaml down
python3 .suite/cyrus/upgrade.py compare --snapshot "$CYRUS_SNAPSHOT_DIR" \
  --volume "$CYRUS_REHEARSAL_VOLUME"
```

The small stage gate checks real admin-authenticated IMAP inventory, LMTP/Sieve
greetings and unauthenticated JMAP denial, then records the verified version.
It does not prove every user's ACLs, message flags or Sieve execution. Before
advancing, run broader read/IMAP checks and inspect private error logs. Repeat
`stage` + start/verify/stop/compare for **3.10.4**, then **3.12.4**. The next stage
refuses to run until the previous running version passed `verify-stage`.
Message comparison checks every original numbered message path and raw hash;
neither body nor subject is printed. Metadata/flags/UIDVALIDITY require protocol
comparison too, and are not implied by raw-byte equality.

At final stage run `.suite/cyrus/probe.py` from a transient Python-capable client
on the rehearsal network with the local CA and credentials mounted read-only:

```sh
python3 probe.py --base-url https://jmap-rehearsal.test:8443 \
  --ca-file /run/test-ca.pem --credentials-file /run/test-credentials.json
```

The credentials file has `username` and `password` keys; never put passwords in
command arguments. Probe checks verified TLS, same-origin discovery/URLs, wrong
password, anonymous access, hostile Host/Origin, same-origin browser access,
no-store, canonical account and actual Mailbox/Email reads. It logs counts only.
Also test bad CA/wrong TLS name, 413 upload bounds, 429 request throttling,
unpublished ports, cross-user/blob denial, actual LMTP delivery/IMAP flags,
ManageSieve filtering, restart persistence, ACLs and independent SMTP/alias send.
Use only synthetic example.test mutations/deliveries. No external mail.

## Protected Endpoint And Promotion

Only after parent review and all rehearsal/protocol gates pass:

1. Take a fresh quiesced snapshot if the live suite has accepted writes since
   the rehearsal snapshot. Repeat the validated upgrade on a fresh clone; do not
   promote a stale rehearsal copy over newer live mail.
2. Set `CYRUS_JMAP_IMAGE` to the reviewed 3.12.4 image and
   `CYRUS_PROMOTED_VOLUME` to the verified upgraded clone, **not**
   `suite-cyrus-data`. Append `.suite/mailbox/cyrus.jmap.yaml` as the final
   top-level Compose overlay after the existing suite files. Paths in this
   overlay are relative to `.suite`, not `mailbox/`.
3. Supply reviewed `JMAP_NGINX_IMAGE`, `JMAP_SERVER_NAME` and `JMAP_TLS_DIR`.
   Render/check the merged configuration privately before `up --no-build`.
   Review service mounts, volume name and image IDs; never dump secret envs to
   shared logs. Preserve the existing IMAP proxy and Robin LMTP aliases/port 24.
4. Start Cyrus and the protected proxy only, check health/canonical access, then
   resume delivery and other clients deliberately. Main Webmail mode stays Robin.
5. Internal TLS discovery is `https://jmap-backend:8443/.well-known/jmap` with
   the default server name. Trust its CA explicitly in the Webmail deployment.
   Raw backend port 8008 is unpublished and belongs to the trusted suite network;
   do not accept a public URL pointing at it. No CORS wildcard or auth bypass.
6. External access is an additional decision: set an explicit public hostname,
   real certificate and bind address, then append `cyrus.jmap.public.yaml`.
   Only TLS 1.2/1.3 is exposed. Test discovery/advertised origins, Host rejection,
   browser Origin policy, auth barrier, connection/request limits and 30 MiB body
   limit first. Streaming requests have a 65-second upstream idle timeout and
   must reconnect; do not claim a durable push implementation from that route.

The proxy strips untrusted forwarding headers, never retries upstream writes,
does not cache responses, and logs only status/method/size/duration. nginx warning
logs can still contain operational request details; restrict access and retention.
Do not configure a trusted public endpoint on an untrusted shared Docker network.

## Rollback

Stop ingress, producers and Cyrus again. Preserve any post-promotion deliveries
and reconcile the queue before rollback; blindly restoring an old snapshot loses
new mail. Restore the immutable pre-upgrade snapshot into a **new**
`robin-cyrus-rollback-*` volume using `upgrade.py restore`. Remove the JMAP/public
overlays, point the legacy Cyrus volume definition at the new rollback volume,
and select the snapshot's exact old `image_id` with builds/pulls disabled. Use
its saved `imapd.conf` and `cyrus.conf` and the original admin secret. Render,
review, start and verify before opening ingress. Never run 3.6 against any
upgraded clone. Never delete the original/snapshot/promoted volumes as cleanup.

Rehearsal cleanup is `docker compose ... down` without `-v`; retain data until
the operator explicitly approves removal of that exact new labelled volume.
There is no global prune/reset, Docker restart or automatic main-mode switch.

## Evidence To Record Before Approval

- [x] Exclusive Docker slot granted; released after fixture cleanup.
- [x] Pinned source builds and config lint on all three synthetic stages.
- [x] Synthetic cold snapshot, new-volume restore and old-image rollback.
- [x] All three staged upgrades and running daemon gates.
- [x] Synthetic raw bytes, IMAP UIDVALIDITY/UID/flags/date/folder checks after restart.
- [x] Two-user IMAP isolation and disabled/alias/wrong-password denial.
- [x] TLS/Host/Origin/auth/size/rate-limit checks and canonical JMAP reads.
- [ ] Production base-image/package security review and immutable deployment pins.
- [ ] Actual suite-data cold backup, separately protected admin secret and clone gate.
- [ ] Actual shared ACL/Sieve execution/quota/subscription and SMTP/alias gates.
- [ ] Parent diff/evidence review and approved maintenance promotion.

Local static tests: `python3 -m unittest discover -s .suite/tests -v`.
Docker tests are intentionally not implied by these mocked unit tests.

### Verified Synthetic Gate (2026-10-28)

Command, run from WSL at `/mnt/d/work/robin` after sequential image builds:

```sh
export CYRUS_LEGACY_IMAGE=sha256:a316f34ceac60edd967243b8f669255f984f42c1fa18725d025cfaf372809b8d
export JMAP_NGINX_IMAGE=sha256:3d6cab9c2941df2441024a2f02a65ebe1b8916c3cbe7b9527f62627accea8004
bash .suite/cyrus/run-upgrade-rehearsal.sh
```

Exit **0**. Log: `log/cyrus-jmap-upgrade-rehearsal-5.log`. Evidence and complete
hashed snapshot: `/tmp/robin-cyrus-rehearsal-2tRa7Cxu` (WSL Ubuntu-24.04).
That private directory contains the synthetic admin secret and test credential
file: do not publish it or print its contents. Preserve the manifest/archive
checksums privately; no production credential or mail was copied.

Projects `robin-cyrus-legacy-73270` and `robin-cyrus-candidate-73270` were removed
with their containers/networks. Retained volumes for review:
`robin-cyrus-legacy-73270`, `robin-cyrus-rehearsal-73270`,
`robin-cyrus-rollback-73270`. Earlier failed-attempt evidence/volumes remain for
diagnosis under suffixes `46208`, `46901`, `71210`, `72116`; not all three volume
types exist for each attempt. Do not remove older/unrelated fixtures by prefix.

All 34 original message files remained byte-identical after each upgrade and
after old-image rollback. Both users retained folder UIDVALIDITY, message UIDs,
flags, internal dates and MIME/attachment bytes. Each daemon gate authenticated
the admin and found 13 mailboxes, with ordinary-user isolation checked separately.
Final JMAP read returned Alice's six mailboxes and 17 messages through verified
TLS. Wrong certificate authority/hostname, anonymous/wrong credentials, hostile
Host/Origin, 31 MiB declared body (413) and request burst (429) all passed.
Restart and the restored old 3.6.1 server passed the same IMAP inventory gate.
Native JMAP SMTP submission was never enabled or attempted.

Built image IDs:

| Version | Image ID |
| --- | --- |
| 3.8.8 | `sha256:5651ac0ee8a9ea1979b21909ce648c704af3b2e330945599bae541c012a6253e` |
| 3.10.4 | `sha256:8e0c84357533078be3064b98c82dddc20f907a3c7844086ba690a183731411fc` |
| 3.12.4 | `sha256:4ce9547bc203f0a5887a13b6cfa477456eeb5e7ce21e928eba517ac9d2ea618c` |

The main Cyrus still uses original image `sha256:a316f34ceac60edd967243b8f669255f984f42c1fa18725d025cfaf372809b8d`
and original volume. All 21 live suite containers were healthy after cleanup;
none was restarted or promoted, and Webmail remains on Robin API. No commit,
push, `.gitignore`, flattened config, database setting or Webmail source edit.

Real rehearsals found and fixed the default child-service config path, public
servername versus admin SASL realm, and `squatter` all-mailbox invocation.
Regression tests cover those failures. A first WSL relative-import/cwd failure
was corrected with absolute host paths/re-anchoring; its underlying host cause
was not established. Source builds/export showed I/O delays, not evidence of an
OOM. The gate never bypassed an uncertain write or retried a failed data stage.

One nonfatal 3.12 config-lint warning remains: legacy `prometheus_update_freq`
is deprecated in favor of `prometheus_service_update_freq`. The shared legacy
config remains unchanged to preserve the older suite. Review this versioned
setting with the actual-data promotion. Strict archive checks also reject
absolute/parent-relative symlinks: inspect any real Sieve layout rather than
weakening the restore guard to make a backup pass.

Standalone/merged Compose rendering and a real networkless `nginx -t` passed.
Final merged-config assertions confirmed the selected external clone, no Cyrus
or JMAP published port and no legacy bind shadowing generated `/etc/imapd.conf`.
`WEBMAIL_MODE` was independently read back as `robin`.
Final `python3 -m unittest discover -s .suite/tests -v`: **43 tests, 42 passed,
one skipped** (the unrelated opt-in whole-suite PostgreSQL/queue Docker drill).
This includes shell syntax, Python parsing, archive/path/owner/image/volume
guards and regression tests for all three service issues. Tracked helper
`git diff --check` passed; branch remains `master` and all work is uncommitted.

### Changed Paths For Parent Review

Only `robin-auth.sh` modifies an existing tracked file; the other paths are new.
Its change moves the password from `jq` process arguments to stdin. Input Cyrus
configs, current Compose defaults and user `.gitignore` remain untouched.

```text
.suite/cyrus/Dockerfile.jmap
.suite/cyrus/jmap-entrypoint.sh
.suite/cyrus/jmap-healthcheck.sh
.suite/cyrus/offline-upgrade.sh
.suite/cyrus/probe.py
.suite/cyrus/rehearsal-auth.py
.suite/cyrus/rehearsal-legacy.yaml
.suite/cyrus/rehearsal-mail.py
.suite/cyrus/rehearsal.yaml
.suite/cyrus/robin-auth.sh
.suite/cyrus/run-upgrade-rehearsal.sh
.suite/cyrus/upgrade.py
.suite/cyrus/verify-stage.py
.suite/etc/cyrus/cyrus-jmap.conf
.suite/etc/cyrus/imapd-jmap.conf
.suite/etc/jmap-proxy/default.conf.template
.suite/etc/jmap-proxy/nginx.conf
.suite/mailbox/cyrus.jmap.public.yaml
.suite/mailbox/cyrus.jmap.yaml
.suite/tests/test_cyrus_upgrade.py
doc/testing/cyrus-jmap-upgrade.md
```

## Upstream References

- [3.12 upgrade guide](https://www.cyrusimap.org/3.12/imap/download/upgrade.html)
- [3.10 upgrade guide](https://www.cyrusimap.org/3.10/imap/download/upgrade.html)
- [3.8 upgrade guide](https://www.cyrusimap.org/3.8/imap/download/upgrade.html)
- [JMAP setup](https://www.cyrusimap.org/3.12/imap/download/installation/http/jmap.html)
- [SMTP backend semantics](https://www.cyrusimap.org/3.12/imap/reference/manpages/configs/imapd.conf.html#std-cyrusman-imapd.conf-5)
- [Official release metadata](https://api.github.com/repos/cyrusimap/cyrus-imapd/releases/tags/cyrus-imapd-3.12.4)

Checked 2026-10-27; version-specific docs are not proof of deployed behavior.
