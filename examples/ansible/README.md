# Ansible: new suite host

An initial-install example for a dedicated Debian/Ubuntu x86-64 Linux Docker
host. It reuses `.suite/` rather than maintaining a second set of mail templates.
It deliberately refuses existing PostgreSQL data or suite containers; it is not
an automatic migration, upgrade, credential rotation or cleanup playbook.
Failures leave state for investigation, not an automatic reset/retry.

Install Docker Engine and Compose 2.24.6+ on the host first. Configure firewall
rules, confirm no conflicting SMTP/IMAP services, and supply a public CA mail
certificate/key in a dedicated root-protected directory. Verify Docker's
published-port firewall behavior externally; UFW alone is not a sufficient
assumption. Manage DNS, certificate issuance and public web HTTPS separately.
Python 3.11+ is required on the host for the recovery tool.

On the controller, install Ansible Core 2.16+; no third-party collections are
required. Prepare the ignored operator files:

```bash
cd examples/ansible
cp inventory.example.ini inventory.ini
cp group_vars/robin_suite.example.yml group_vars/robin_suite.yml
cp group_vars/robin_suite.vault.example.yml group_vars/robin_suite.vault.yml
# Set the actual host, reviewed 40-character Git commit, public hostnames,
# immutable image digests, and independent secrets (openssl rand -hex 32 each).
ansible-vault encrypt group_vars/robin_suite.vault.yml
ansible-playbook -i inventory.ini site.yml --ask-vault-pass --syntax-check
ansible-playbook -i inventory.ini site.yml --ask-vault-pass --ask-become-pass
```

`robin_suite_network_ready` is an explicit operator prerequisite acknowledgment,
not a firewall check. Pin PostgreSQL 18 and Dovecot 2.4-compatible images. The
playbook validates digest syntax, not compatibility or supply-chain provenance.
It generates file-backed credentials only if absent, writes `.env` mode 0600
without overwriting it, and never forces Git resets.

Back up the generated secrets immediately. Keep Admin and webmail on loopback;
use an SSH tunnel or the [HTTPS proxy example](../docker-compose/nginx.conf.example).
Admin's own `ROBIN_ADMIN_PUBLIC_URL` must match the HTTPS URL clients use.
Provision mailboxes and complete mail acceptance before pointing MX records here.
Container health alone does not establish production readiness.

For later changes, use the [operations guide](../../doc/developer/suite-operations.md),
not a rerun of this new-install playbook. Automated off-host backup scheduling
and restore drills belong to the operator's infrastructure; do not store Vault
passwords, filled-in secrets or backups in Git.
