# Suite mailbox backends

The suite stores mailboxes in either **Dovecot** (the default) or **Cyrus IMAP**.
Both are open source and use the same Robin users, aliases and auto aliases, so
either one can be chosen when the suite is set up. Robin delivers mail to the
selected backend over LMTP and webmail, mail clients and Robin Admin connect to
it through the same role aliases.

Choose the backend before the first start by setting `MAILBOX_BACKEND` in
`.suite/.env`:

```bash
MAILBOX_BACKEND=dovecot   # or cyrus
```

Compose then includes `.suite/mailbox/<backend>.yaml` (and
`<backend>.production.yaml` with the production overlay). Only the selected
backend's containers are created.

## Comparison

| | Dovecot | Cyrus IMAP |
|---|---|---|
| Containers | `suite-dovecot` | `suite-cyrus`, `suite-imap-proxy` (nginx) |
| IMAPS on port 993 | Dovecot | nginx mail proxy in front of Cyrus |
| LMTP (private, port 24) | Dovecot | Cyrus |
| Login with an alias or auto alias | Yes, via SQL passdb | Yes, the proxy asks Robin to resolve it |
| Password checks | Dovecot reads the users table | Cyrus asks Robin over the private API |
| Mailbox creation | On first delivery or login | On first delivery or login |
| Default folders | Sent, Drafts, Trash, Junk, Archive with SPECIAL-USE | Drafts, Sent, Trash, Junk |
| Quota | 20 GiB, including IMAP APPEND and LMTP | 20 GiB |
| Sieve filters | ManageSieve 4190, scripts under `~/sieve` | ManageSieve on port 4190 (private network) |
| Mail data | `store/dovecot` | Named volume `suite-cyrus-data` |
| Robin Admin fleet service | Dovecot | Cyrus |

## How the Cyrus backend works

```
Email client / Roundcube ──IMAPS 993──▶ suite-imap-proxy (nginx)
                                          │  auth_http
                                          ├──────────────▶ Robin /mail/proxy-auth
                                          │                 (user, alias or auto alias)
                                          ▼
                                       suite-cyrus :143  ──PAM──▶ Robin /users/authenticate
Robin MTA ─────────────LMTP 24──────────▶ suite-cyrus
```

- **Logins.** The nginx mail proxy terminates TLS and asks Robin's
  [mail proxy authentication](../lib/dovecot-sasl.md#mail-proxy-authentication)
  endpoint to check each login. Robin resolves aliases and auto aliases to the
  owning user, checks the password and tells nginx to log in to Cyrus as the
  canonical address, so every address opens the same mailbox.
- **Passwords.** Cyrus checks passwords through `saslauthd` and PAM, which call
  Robin's `/users/authenticate` endpoint. Cyrus never reads the database.
- **Delivery.** Robin resolves aliases before delivery and sends only local
  recipients over LMTP. Cyrus creates the mailbox with its default folders and
  quota on first delivery.
- **Monitoring.** `promstatsd` writes usage statistics in Prometheus format
  to `/var/lib/cyrus/config/stats/report.txt`. Robin Admin's suite agent reads
  them, together with active sessions and quotas, for the Cyrus fleet service.
- **Administration.** Cyrus has a `cyrus` admin account whose password is the
  `secrets/cyrus_admin_password` file created by `init-secrets.sh`. It is only
  used inside the suite network, for example by Robin Admin and migrations.

The proxy authentication endpoint is enabled in `.suite/cfg/dovecot.json5`
(`proxyAuth`) for new installations. The PostgreSQL configuration store is
authoritative after the first start, so an existing installation must enable it
through Robin Admin (Dovecot configuration) or its configuration store:

```json5
proxyAuth: {
  enabled: true,
  imap: "mailbox-backend:143",
  pop3: "",
  failureWait: 3
}
```

Production Robin APIs require the same independent Basic credential supplied to
Admin. Mailbox authentication clients must send that service credential separately
from the user's credentials. Do not expose either API through the public proxy.

## Dovecot 2.4 mailbox controls

The suite enables IMAP, LMTP and private ManageSieve, not POP3. The privileged
master still starts as root; mailbox workers use SQL UID/GID 5000 and the image's
`vmail` group for the group-readable userdb socket. Removing that socket access
breaks shared-owner lookups. Authentication-master and LMTP Unix sockets remain
root-only; there are no world-writable authentication sockets.

The reviewed 2.4.2 image includes Flatcurve, not Lucene. Full-text indexing is
opt-in: enable `fts` and `fts_flatcurve` together in `90-fts.conf`, then schedule
bounded `doveadm index` work after measuring disk/CPU usage. Background indexing
is off. Search remains available without FTS.

Mailbox sharing uses real Dovecot vfile ACLs and a backed-up sharing map at
`store/dovecot/shared-mailboxes.db`, not an unapplied SQL membership table:

```bash
# Example only: operate on a maintenance-approved deployment, not a live fixture.
docker compose --project-directory .suite exec suite-dovecot \
  doveadm acl set -u owner@example.com INBOX user=member@example.com lookup read write-seen
docker compose --project-directory .suite exec suite-dovecot \
  doveadm -f tab acl get -u owner@example.com INBOX
docker compose --project-directory .suite exec suite-dovecot \
  doveadm acl delete -u owner@example.com INBOX user=member@example.com
```

The member opens `Shared/owner@example.com/INBOX`. New users have no shared
access. Grant only the required folder rights; `admin` would let a member change
ACLs. Reconnect sessions after revocation to discard per-process ACL caches.
Access to a mailbox **does not grant send-as**: that requires a separate explicit
`mail_sender_grants` entry and enabled managed domain in Robin.

Password authentication caching is disabled so the next login observes a changed
hash. Existing authenticated sessions are not revoked by a password change:
explicitly disconnect them with a reviewed `doveadm kick` operation when needed.

ManageSieve stores scripts in `~/sieve` and uses `~/sieve/.active.sieve`. Existing
deployments must explicitly move/re-upload old `~/.dovecot.sieve` scripts after
backing them up. In the legacy schema home is also the Maildir root; dot-prefixed
script files/symlinks there can be mistaken for Maildir++ folders during quota
enumeration. Remove that collision only after preserving the original script.
The SQL userdb now returns Dovecot 2.4 `mail_path`, preserving the existing
`maildir:` path or falling back to home; it does not relocate mail.

TLS and basic fileinto Sieve rules are covered by isolated acceptance. An outbound
vacation/redirect transport is **not configured by enabling ManageSieve**. Provision
an authenticated, sender-authorized submission route and test loop suppression
before enabling automatic replies or external redirects. There is no trusted
Docker-subnet relay bypass.

## Production

With the production overlay set the backend images and the shared TLS directory
in `.suite/.env`:

| Backend | Required variables |
|---------|--------------------|
| Dovecot | `DOVECOT_IMAGE`, `MAIL_TLS_DIR` |
| Cyrus | `CYRUS_IMAGE`, `NGINX_IMAGE`, `MAIL_TLS_DIR` |

`CYRUS_IMAGE` is built from `.suite/cyrus/Dockerfile`. For Cyrus the
certificate is served by the proxy; restart `suite-imap-proxy` (instead of
`suite-dovecot`) after renewing it. The Cyrus data volume is included in
`.suite/recovery.py` backups like the other named volumes.

## Switching an existing Dovecot installation to Cyrus

Switching is optional and is not done automatically. The steps below copy every
mailbox with [imapsync](https://imapsync.lamiral.info/) while mail is stopped.
Message flags, dates and folders are preserved. Sieve scripts are not copied;
upload each user's `.dovecot.sieve` to Cyrus afterwards, for example with
`sieveshell` from inside `suite-cyrus`.

1. **Back up** the deployment, including `store/dovecot`, as described in
   [Suite operations](suite-operations.md).
2. **Stop mail services** so nothing changes during the copy:

   ```bash
   docker compose --project-directory .suite stop suite-robin suite-roundcube suite-dovecot
   ```

3. **Select Cyrus** by setting `MAILBOX_BACKEND=cyrus` in `.suite/.env` (and
   the production images if used), then create the new secret and start Cyrus:

   ```bash
   .suite/init-secrets.sh
   docker compose --project-directory .suite up -d --wait suite-cyrus
   ```

4. **Copy the mailboxes.** The migration tool starts a temporary Dovecot over
   `store/dovecot` with a one-off master user and runs imapsync for every user
   in the database (or only the addresses given as arguments):

   ```bash
   .suite/migration/migrate-mailboxes.sh
   ```

   Extra imapsync options can be passed in `IMAPSYNC_OPTIONS` (for example
   `--dry` for a trial run), and an extra Compose file in
   `MIGRATION_COMPOSE_OVERRIDE` if the store lives somewhere else. imapsync is
   incremental, so the tool can be run again to retry failed users.

5. **Enable proxy authentication** in Robin's configuration as shown above.
6. **Start the suite** and check a few mailboxes in webmail and a mail client:

   ```bash
   docker compose --project-directory .suite up -d --wait --remove-orphans
   ```

7. **Clean up** after the new backend has been verified: delete
   `secrets/migration_master_password`, and keep `store/dovecot` until it is no
   longer needed for rollback.

To roll back, stop the suite, set `MAILBOX_BACKEND=dovecot` and start it again;
`store/dovecot` is not modified by the copy apart from Dovecot's index files.
Mail delivered to Cyrus after the switch is not copied back.

## Limitations

- POP3 is not offered by either backend in the suite.
- ManageSieve on Cyrus accepts only the primary address, not aliases.
- Roundcube submits mail with the login name; log in with the primary address
  to send mail when the login alias is not itself an SMTP account.
