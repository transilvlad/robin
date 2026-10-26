# Suite operations and recovery

The suite is a production-oriented baseline, not a guarantee that an arbitrary
host is production-ready. Use a dedicated Linux Docker host, a reviewed Git
release, trusted image pins and the [deployment examples](../../examples/docker-compose/README.md).
Never run the fault-injection `.integration/` tests against this deployment.

## What must survive

| State | Location |
|-------|----------|
| Users, aliases, queue records, authoritative config, Admin settings, Roundcube database | PostgreSQL under `store/postgres` |
| Pending message bodies and Robin storage | `store/robin` and any explicitly configured queue files |
| Mailboxes | `store/dovecot` (Dovecot) or the `suite-cyrus-data` named volume (Cyrus IMAP) |
| Rspamd statistics/state | Redis and Rspamd named volumes; `store/rspamd` and `.suite/etc/rspamd/local.d` |
| Admin identity, gateway CA, agent/installer state, telemetry | Included Admin named volumes |
| Bootstrap/config templates and service configuration | `.suite/cfg`, `.suite/etc`, Compose files |
| Credentials/encryption keys | `.suite/.env`, `.suite/secrets`, DKIM keys, operator TLS directory |

Restoring queue records without their message bodies can lose accepted mail.
Restoring fresh Admin encryption keys can make stored credentials unreadable;
restoring a different Roundcube session key invalidates sessions. Treat this as
one recovery unit. Database configuration remains authoritative: changing a
checked-in template does not change an existing database row or config cache.

## Restart after a host or Docker interruption

Robin, PostgreSQL, Redis, scanning services and the selected mailbox backend use
`restart: unless-stopped`. Explicitly stopped containers remain stopped. This
policy does not restart a process merely because its health check fails or
guarantee dependency health ordering when Docker restarts.

Inspect service health and logs before resuming mail flow. Use the normal Compose
configuration with the selected mailbox backend to bring services back up;
do not enable both mailbox backends. Preserve existing volumes and configuration.
Recreate affected containers when applying changed images or container
configuration; do not rerun fresh-install initialization as a recovery shortcut.

## Cold backup

`.suite/recovery.py` provides an explicit maintenance-window snapshot. It requires
root, Python 3.11+, the local default Docker context, Git and Docker Compose. It
does not support a remote Docker daemon, externally hosted databases, external
PostgreSQL tablespaces, or message files outside Compose mounts. Additional
module stacks deployed by the installer are separate projects: back them up
and stop their database writers too. Database clients outside Docker must be
quiesced by the operator.

Run from the reviewed checkout after setting the same `COMPOSE_FILE` in
`.suite/.env` that was used for deployment. Use an absolute destination outside
all mounted directories, with sufficient room for data **and runtime images**:

```bash
docker image inspect alpine:3.23 >/dev/null || docker pull alpine:3.23
sudo python3 .suite/recovery.py backup /srv/robin-backups/2026-10-11-before-upgrade
```

The tool takes a host-local exclusive operation lock, stops every running
service in this Compose project (including PostgreSQL), verifies there are no
remaining running project containers or other mounted-data writers, then copies
bind-mounted state and existing named volumes with numeric ownership preserved.
Logs and the Docker socket are excluded. This is a **cold physical PostgreSQL
snapshot**, not a portable logical dump. It also saves actual runtime images,
image IDs, the Git revision, deployment inputs and previously running services.
It does not rely on an image's mutable tag still pointing to the same build.
The current Compose inputs must match the containers' recorded configuration
hashes; take the backup **before** editing deployment settings. Missing mounted
data and unconfigured anonymous/runtime volumes stop backup instead of producing
a success-shaped incomplete archive.

`COMPLETE` is written only after archives and checksums finish. A failure leaves
an incomplete snapshot for investigation; never restore it or delete the
previous good snapshot. Neither success nor failure restarts services
automatically. Keep mail unavailable until the snapshot is complete, then
explicitly restart the previously running services listed in `manifest.json`.
Do not enable previously inactive profiles while resuming.

The snapshot contains database password hashes, private keys, application
secrets and mail. It is root-private, **not encrypted**. Encrypt and copy it
off-host using your authenticated backup system; keep a previous generation,
monitor job failures and available disk, and test decryption and restore
regularly. Checksums detect accidental corruption, not a malicious replacement.
Only restore operator-trusted snapshots. No pruning or remote upload is done
automatically.

For larger installations, replace the downtime-heavy physical snapshot with
coordinated database/filesystem snapshots or a PostgreSQL-aware backup system.
An independent `pg_dump` is useful for logical migration but is not by itself a
consistent backup of queue payloads, mailboxes or separate module databases.

## Fresh-host restore

Restore onto a dedicated host with **no suite containers**, empty data
directories and empty/absent suite volumes. The tool refuses to overwrite
existing data; a partially failed restore needs operator investigation or a
new empty target, not an automatic cleanup. Fixed names prevent safely running
two suites on the same Docker daemon. Keep public mail/DNS cut over only after
verification.

1. Verify/decrypt a trusted complete snapshot. Read its `manifest.json`; check
   the recorded Git revision, images and mount set. Retrieve that same source
   revision. Keep the snapshot outside the new checkout.
2. Restore deployment inputs from `deployment.tar` into the new `.suite/`
   **before** resolving Compose. Review `.env` paths; relocate `MAIL_TLS_DIR`
   to an empty dedicated directory on the new host if needed. Keep the same
   mounted basenames and service/target mapping. Copy the file-backed secrets
   and retain the original encryption keys; do not run `init-secrets.sh` first.
3. Run the recovery command below. Existing deployment files/directories must
   match the snapshot; existing mail/database/queue data must be empty.
   The tool restores TLS and data mounts, named volumes and saved images, but
   never starts services or runs new-database init scripts.

```bash
# Extract only a trusted operator snapshot. This contains secrets.
sudo tar -xpf /srv/restore/deployment.tar -C /opt/robin/.suite
sudo chmod 600 /opt/robin/.suite/.env
cd /opt/robin
sudo python3 .suite/recovery.py restore /srv/restore --confirm-fresh-target

# Use the exact old runtime images. The image lock suppresses builds and pulls.
# Include the production overlay only if it was used by the original deployment.
docker compose --project-directory .suite \
  -f .suite/docker-compose.yaml -f .suite/docker-compose.override.yaml \
  -f .suite/docker-compose.production.yaml -f /srv/restore/images.yaml \
  config --quiet
docker compose --project-directory .suite \
  -f .suite/docker-compose.yaml -f .suite/docker-compose.override.yaml \
  -f .suite/docker-compose.production.yaml -f /srv/restore/images.yaml \
  up -d --no-build --pull never --wait --wait-timeout 600
```

The bootstrap Alpine image is not a saved persistent runtime: pre-pull the
reviewed image on the target. Pin remote Admin Compose includes to the recorded
release too; an image archive alone does not replace those source definitions.
Do not restore to a new PostgreSQL major version. A physical backup is tied to
its platform and database binary version; recover the old deployment first and
perform a separate logical migration/`pg_upgrade` later.

Before DNS cutover, verify mailbox users/aliases and Admin access, the config
store and cache, queue counts and representative payload paths, mailbox
ownership (5000:5000), TLS identity/expiry, SMTP/IMAPS and successful delivery.
Recovering the same queue may resend mail whose remote acceptance occurred
before a lost local acknowledgment: duplicates are possible. Never clear the
queue to make restore look healthy.

## Upgrade and rollback

Do not run unattended `git pull && docker compose pull && up` on live mail.
Review source changes, database schemas and every changed image digest. Pin
the release and external images; save the old runtime image set.

For local Maven builds after dependency upgrades, use `mvn -B clean package` to
remove superseded JARs from `target/classes/lib`. JDK image updates require
rebuilding and recreating the affected containers. Verify `java -version` in
the build and runtime images for each deployed architecture, then check the
running JVM and mail/TLS flows after deployment.

1. Schedule a maintenance window, quiesce clients/automation and take a complete
   snapshot. Confirm the snapshot exists off-host before changing state.
2. Change to the reviewed source/image release without discarding local
   configuration or operator edits. Do not use forced Git resets. Keep secrets
   stable unless credential rotation is intentionally coordinated.
3. Compare Compose output without sharing resolved secrets. Build the new Robin
   image with `INCLUDE_CFG=false`; test on a separate host first. Persisted config
   changes go through Admin or an explicit transaction in the config store;
   check base **and per-server overrides**, not just `.suite/cfg`.
4. Start and verify services, then verify mail flow and queue progression before
   admitting live traffic. Health checks only establish process/service health.

An incompatible SQL queue schema now stops startup without renaming or deleting
the old table. Back up and explicitly migrate/drain pending mail using compatible
software; adding empty columns or choosing a new table is not a migration.
See [queue upgrades](../features/queue.md#sql-startup-and-upgrades).

Rollback is not just changing an image tag after schema migrations. If the new
release mutated state, stop it and recover the coherent pre-upgrade snapshot
onto a fresh target. Account for mail accepted since that snapshot: restoring
older data can lose new mail. Keep the newer state for reconciliation, rather
than deleting it. Do not use `down -v`, delete `store/`, or auto-empty a queue.

## Existing demo installation: explicit migration

New-database initialization scripts run **only on an empty PostgreSQL data
directory**. The deployment examples do not convert an old privileged `robin`
installation automatically. Perform this work with a maintenance superuser in
a planned outage and rehearse against a restored copy first:

1. Inventory databases, table/sequence ownership, users, queue schema/payloads,
   named volumes, literal credentials, config-store overrides and installed
   modules. Take a coherent backup before stopping the old service set.
2. Create/rotate the scoped `robin_mta`, `robin_mailbox`, `robin_admin`,
   `robin_roundcube` and `robin_monitor` roles using independent external
   credentials. Keep a maintenance superuser unavailable to applications.
   Follow `.suite/db-init/00-create-roles.sh` for intended grants, but **do not
   blindly rerun it**: it contains CREATE statements for an empty installation.
   Apply only reviewed role/grant changes with safely bound password values.
3. Transfer Robin's existing config/queue tables and their sequences to
   `robin_mta`, Admin-owned tables/sequences to `robin_admin`, and the Roundcube
   database/tables to `robin_roundcube`. Grant directory SELECT to MTA/mailbox,
   mailbox-directory DML to Admin, and Admin access to Robin config/queue.
   Apply future-object default privileges under the actual object-owning role.
   Do not use `REASSIGN OWNED BY robin` blindly: the old role may own unrelated
   data. Do not delete the old role until all dependencies have been audited.
4. Replace stored literals with external-secret references in every applicable
   config layer. Configure required config-store sync, a writable `robin-config`
   cache, and the read-only bootstrap file. Ensure deployed Admin-managed rows
   have the production mail/TLS/scanner policy, not stale test behavior.
5. Supply public TLS, secrets and the independent Roundcube session key. Verify
   mailbox path/ownership and backend connectivity. Remove or rotate sample
   users only after checking their mail/data; never delete them automatically.
6. Validate each new role with its real application queries and disallowed
   operations, then start services in dependency order and perform acceptance.
   Keep the rollback snapshot and old state until the migration is accepted.

This intentionally requires operator choices about existing data/ownership;
there is no catch-all migration script that guesses which tables are safe.

## Certificate renewal

The mail hostname must have matching forward/reverse DNS and a CA-issued chain.
Obtain certificates through your ACME/DNS provider; port-25 mail handling does
not itself serve HTTP-01. Keep renewal automation separate from the application.

Use a dedicated certificate directory rather than mounting just individual
files: single-file bind mounts can keep pointing to an old inode after renewal.
Avoid symlinks into unmounted ACME archive directories. Stage the renewed chain
and matching PKCS8 key, check hostname/expiry/key correspondence with OpenSSL,
then replace both together during a planned restart. Keep previous files for
rollback; do not change keys mid-handshake or leave a partial pair live.

```bash
openssl x509 -in /etc/robin/mail-tls/fullchain.pem -noout -checkhost mail.example.com
openssl x509 -in /etc/robin/mail-tls/fullchain.pem -noout -checkend 604800
# Compare the two public-key digests:
openssl x509 -in /etc/robin/mail-tls/fullchain.pem -pubkey -noout |
  openssl pkey -pubin -outform DER | openssl dgst -sha256
openssl pkey -in /etc/robin/mail-tls/privkey.pem -pubout -outform DER |
  openssl dgst -sha256
# After verified replacement, reload startup-loaded mail certificates
# (suite-imap-proxy instead of suite-dovecot with the Cyrus backend):
docker compose --project-directory .suite restart suite-robin suite-dovecot
```

Validate the served chain and hostname externally on SMTP STARTTLS, SMTPS and
IMAPS after renewal; alert before expiry and on renewal/restart failure.
Webmail/Admin HTTPS certificates and the suite gateway's internal CA are separate
identities. Update trusted proxy CA files intentionally, never disable upstream
verification to hide a renewal problem.

## Monitoring and remaining acceptance

Alert on disk/inode exhaustion, queue growth/age/dead items, delivery failures,
database/scanner outages, backup age/failure and certificate expiry. Container
log rotation in the production overlay bounds Docker stdout logs, **not**
application files under `log/`; configure host retention/logrotate for those too.
Robin's suite Log4j configuration keeps pipeline logging at WARN, startup and
structured SMTP outcomes at INFO, rolls at 50 MB/daily, and prunes matching
compressed archives after 14 days or above 1 GB at rollover. Existing cache
volumes retain their old `log4j2.xml`: replace that cache file explicitly during
maintenance to adopt the new policy; it is not a config-store JSON row.
Do not prune pending payloads with age-only filesystem cleanup.

Runtime database roles still have CREATE rights for startup migrations; Admin's
agent/installer have Docker-socket privilege. Private management APIs currently
rely on network isolation and can expose effective secrets. Treat network
membership, host access and reverse-proxy configuration as privileged.

Production acceptance remains a separate phase: fresh-host restore drill with
mail, TLS renewal, inbound/submission/relay-boundary checks, durable queue outage
recovery, and deployment-specific MX/PTR/SPF/DKIM/DMARC/signing provisioning.

## Recovery tooling checks

Run refusal/checksum/unit checks on Linux without touching a deployment:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 .suite/tests/test_recovery.py
```

The opt-in Docker drill creates a uniquely named disposable PostgreSQL/mail
fixture, seeds a queue record and payload plus a mailbox owned by 5000:5000,
takes a cold snapshot, restores it with the saved image IDs, and checks restored
contents and ownership. It removes only its own fixture containers and volumes.
Pre-pull `alpine:3.23` and `postgres:18-alpine`, then run on a local Linux Docker
host with sufficient disk space:

```bash
ROBIN_RECOVERY_DOCKER_TEST=1 PYTHONDONTWRITEBYTECODE=1 python3 .suite/tests/test_recovery.py
```

This small rehearsal is not the full production suite acceptance or a live
backup of your mail installation.
