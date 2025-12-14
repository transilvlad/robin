#!/usr/bin/env bash
set -euo pipefail

# Ensure DH params exist (fallback if build stage missed or volume override).
if [ ! -f /etc/dovecot/dh.pem ]; then
  openssl dhparam -out /etc/dovecot/dh.pem 2048
  chown vmail:vmail /etc/dovecot/dh.pem || true
fi

# Generate self-signed cert if missing.
if [ ! -f /etc/dovecot/certs/imap.crt ] || [ ! -f /etc/dovecot/certs/imap.key ]; then
  mkdir -p /etc/dovecot/certs
  openssl req -x509 -newkey rsa:2048 -days 365 -nodes \
    -subj "/CN=localhost" \
    -keyout /etc/dovecot/certs/imap.key -out /etc/dovecot/certs/imap.crt
  chown vmail:vmail /etc/dovecot/certs/imap.key /etc/dovecot/certs/imap.crt || true
  chmod 600 /etc/dovecot/certs/imap.key
fi

# Provision sieve directory structure if missing.
if [ ! -d /var/lib/dovecot/sieve ]; then
  mkdir -p /var/lib/dovecot/sieve/global /var/lib/dovecot/sieve/before /var/lib/dovecot/sieve/after
  # Minimal default sieve script (no-op delivery) if absent.
  if [ ! -f /var/lib/dovecot/sieve/global/default.sieve ]; then
    echo "require \"fileinto\";" > /var/lib/dovecot/sieve/global/default.sieve
  fi
  chown -R vmail:vmail /var/lib/dovecot/sieve || true
fi

mkdir -p /var/mail/vhosts
chown -R vmail:vmail /var/mail
find /var/mail -type d -exec chmod 2775 {} \;
find /var/mail -type f -exec chmod 0664 {} \;
