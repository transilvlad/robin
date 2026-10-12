# Production-oriented Compose deployment

This example reuses `.suite/`, including its public Robin Admin release, external
secrets, TLS, private management network, scanners and real mailbox delivery.
It does not copy mail configuration or enable test features.

Use a dedicated Linux Docker host with Git, Python 3.11+, OpenSSL and Docker
Compose 2.24.6+ (supporting remote includes and `!override`). Budget memory for
ClamAV and the JVM. Only one suite can occupy this host: container, network and
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
sudo bash .suite/init-suite.sh
```

Supply a public CA-issued `fullchain.pem` and matching unencrypted PKCS8
`privkey.pem` in a dedicated `MAIL_TLS_DIR`; no example private key is supplied.
Set a real `MAIL_HOSTNAME`. Copy certificate contents, not dangling symlinks into
another directory. Keep the private key root-readable only.

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

These are placeholders, not pullable image names. Select images compatible with
the checked-out release; digest pins (`image@sha256:...`) are preferred. Do not
change PostgreSQL major versions through a Compose pull. Robin is built locally
with `INCLUDE_CFG=false`, so its tagged image does not embed demo credentials.
Admin's Git include is version-pinned by `ROBIN_ADMIN_VERSION`.

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
Roundcube connects to verified IMAPS and authenticated STARTTLS submission.

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
