# Production-oriented Compose deployment

This example reuses `.suite/`, including its public Robin Admin release, external
secrets, TLS, private management network, scanners and real mailbox delivery.
It does not copy mail configuration or enable test features.

Use a dedicated Linux Docker host with Git, Python 3.11+, OpenSSL and Docker
Compose 2.24.6+ (supporting remote includes and `!override`). Budget memory for
ClamAV and the JVM: core production limits total 9.5 GiB with Dovecot, plus
Admin/observability/modules and host headroom. Plan from 16 GiB, then measure;
this is not a user-count guarantee or an 8 GiB full-stack profile. See
[resource budgets](../../doc/developer/suite-operations.md#single-host-resource-baseline).
Only one suite can occupy this host: container, network and
some volume names are deliberately fixed. Docker group membership is root-level
access. Configure the host/cloud firewall before starting: public mail uses
25/465/587/993; keep PostgreSQL, scanners, LMTP and APIs private. Docker-published
ports can bypass UFW rules, so verify the effective firewall from another host.

## New installation

```bash
git clone https://github.com/transilvlad/robin.git /opt/robin
cd /opt/robin
git checkout <reviewed-release-tag-or-commit>
cp .suite/.env.example .suite/.env
chmod 600 .suite/.env
# Fill every required value, including independent random secrets and TLS paths.
bash .suite/init-secrets.sh
# Set REDIS_ADMIN_DSN from the newly generated secrets/redis_password.
sudo bash .suite/init-suite.sh --production
```

Supply a public CA-issued `fullchain.pem` and matching unencrypted PKCS8
`privkey.pem` in a dedicated `MAIL_TLS_DIR`; no example private key is supplied.
Set a real `MAIL_HOSTNAME` and, with Dovecot, a monitored `POSTMASTER_ADDRESS`.
Provision that postmaster mailbox or alias before routing mail to the host.
Copy certificate contents, not dangling symlinks into
another directory. Keep the private key root-readable only.
Mail listeners default to loopback. Set `MAIL_BIND` to the intended public
interface only after reviewing firewall rules. Set an independent
`ROBIN_API_AUTH=username:password` for Robin and Admin; private APIs remain
unpublished and Basic authentication does not replace network isolation.
Keep `ROBIN_ADMIN_VERSION=1.5.1` or a reviewed compatible release; 1.1.x does not
authenticate configuration reloads. Set `SUITE_LOG_MOUNT` to the checkout's
absolute `log` directory. Set `REDIS_ADMIN_DSN` to
`redis://:<contents of secrets/redis_password>@redis-backend:6379` so Admin uses
the same Redis credential as Rspamd.

For the production overlay add these to `.suite/.env`:

```dotenv
ROBIN_IMAGE=robin-suite:<reviewed-commit>
POSTGRES_IMAGE=postgres:<reviewed-18-tag-or-digest>
REDIS_IMAGE=redis:<reviewed-tag-or-digest>
CLAMAV_IMAGE=clamav/clamav:<reviewed-tag-or-digest>
RSPAMD_IMAGE=rspamd/rspamd:<reviewed-tag-or-digest>
DOVECOT_IMAGE=dovecot/dovecot:<reviewed-2.4-tag-or-digest>
ROUNDCUBE_IMAGE=roundcube/roundcubemail:<reviewed-tag-or-digest>
```

With `MAILBOX_BACKEND=cyrus`, replace `DOVECOT_IMAGE` with the locally built
Cyrus image tag and a reviewed nginx image for the IMAP proxy:

```dotenv
CYRUS_IMAGE=robin-suite-cyrus:<reviewed-commit>
NGINX_IMAGE=nginx:<reviewed-alpine-tag-or-digest>
```

These are placeholders, not pullable image names. Select images compatible with
the checked-out release; digest pins (`image@sha256:...`) are preferred. Do not
change PostgreSQL major versions through a Compose pull. Robin is built locally
with `INCLUDE_CFG=false`, so its tagged image does not embed demo credentials.
Admin's Git include is version-pinned by `ROBIN_ADMIN_VERSION`.
Unpublished local Admin Compose inputs are opt-in through
`ROBIN_ADMIN_COMPOSE_FILE` and `ROBIN_ADMIN_SUITE_COMPOSE_FILE`; leave both unset
to use the pinned public release. The suite does not silently select a sibling
checkout. Confirm that the selected Admin release sends `ROBIN_API_AUTH` on API
and reload requests.

```bash
# Validate without printing resolved credentials:
export ROBIN_REPO_DIR="$(pwd)"
docker compose -f examples/docker-compose/compose.yaml config --quiet
docker compose -f examples/docker-compose/compose.yaml build
docker compose -f examples/docker-compose/compose.yaml up -d --wait --wait-timeout 600
docker compose -f examples/docker-compose/compose.yaml ps
```

`--wait` is a container-health check, not production acceptance. Provision actual
users/domains through Admin before pointing MX records here. Verify inbound mail,
submission, mailbox delivery and relay restrictions before routing live traffic.
Production enables managed recipient routing, explicit sender grants and
bounded account/domain submission quotas. Maintain domain/grant rows when
provisioning users; a receiving alias alone does not authorize sending.
See [production mail policy](../../doc/developer/robin-suite.md#production-mail-policy).
Roundcube connects to verified IMAPS and authenticated STARTTLS submission.
Provision DKIM keys and DNS before accepting outbound mail: production requires
fresh DKIM signing and temporarily refuses unsigned authenticated submissions. See
[scanner credentials and DKIM](../../doc/developer/robin-suite.md#scanner-credentials-and-dkim)
for key paths, missing-key behavior and coordinated Redis/controller rotation.

Admin is loopback HTTPS on 3443; webmail is loopback HTTP on 8888. Use an SSH
tunnel for initial Admin setup or an appropriately secured HTTPS reverse proxy.
The [Nginx example](nginx.conf.example) shows both upstreams without exposing the
private Robin APIs. Supply separate certificates for the web hostnames.
For the Admin upstream, copy the gateway's internal CA through the local Docker
socket (`docker cp suite-admin-gateway:/data/caddy/pki/authorities/local/root.crt /etc/robin/admin/gateway-ca.pem`) and verify that identity before trusting it.
The proxy uses `localhost:3443` upstream because the bundled Caddy sites are
`localhost`/`admin-gateway`, not the public Admin hostname.
Do not enable the Docker-socket installer unless it is required and trusted.

## Operations

Use the [suite operations guide](../../doc/developer/suite-operations.md) for
backups, fresh-host restore, upgrades, existing-demo migration and TLS renewal.
The recovery tool uses `.suite` as its Compose project directory. When using
this example, put the equivalent file list in `.suite/.env` so operational
commands use precisely the same model:

```dotenv
COMPOSE_FILE=docker-compose.yaml:docker-compose.override.yaml:docker-compose.production.yaml
```

Run this example from the repository root as shown. Do not use it alongside a
second `.suite` deployment, and never use `down -v` on a live deployment.
`ROBIN_REPO_DIR` is absolute so nested public Admin includes and their environment
file resolution do not depend on the invoking shell's directory.
