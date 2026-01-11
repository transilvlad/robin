#!/usr/bin/env bash
set -euo pipefail

have_vmail=false
if id -u vmail >/dev/null 2>&1; then
  have_vmail=true
fi

# Ensure DH params exist (fallback if build stage missed or volume override).
if [ ! -f /etc/dovecot/dh.pem ]; then
  if [ -w /etc/dovecot ]; then
    openssl dhparam -out /etc/dovecot/dh.pem 2048
    if [ "$have_vmail" = "true" ]; then
      chown vmail:vmail /etc/dovecot/dh.pem || true
    fi
  else
    echo "Skipping dh.pem generation: /etc/dovecot is read-only" >&2
  fi
fi

# Generate self-signed cert if missing.
if [ ! -f /etc/dovecot/certs/imap.crt ] || [ ! -f /etc/dovecot/certs/imap.key ]; then
  if [ -w /etc/dovecot ]; then
    mkdir -p /etc/dovecot/certs
    openssl req -x509 -newkey rsa:2048 -days 365 -nodes \
      -subj "/CN=localhost" \
      -keyout /etc/dovecot/certs/imap.key -out /etc/dovecot/certs/imap.crt
    if [ "$have_vmail" = "true" ]; then
      chown vmail:vmail /etc/dovecot/certs/imap.key /etc/dovecot/certs/imap.crt || true
    fi
    chmod 600 /etc/dovecot/certs/imap.key
  else
    echo "Skipping cert generation: /etc/dovecot is read-only" >&2
  fi
fi

# Provision sieve directory structure if missing.
if [ ! -d /var/lib/dovecot/sieve ]; then
  mkdir -p /var/lib/dovecot/sieve/global /var/lib/dovecot/sieve/before /var/lib/dovecot/sieve/after
  # Minimal default sieve script (no-op delivery) if absent.
  if [ ! -f /var/lib/dovecot/sieve/global/default.sieve ]; then
    echo "require \"fileinto\";" > /var/lib/dovecot/sieve/global/default.sieve
  fi
  if [ "$have_vmail" = "true" ]; then
    chown -R vmail:vmail /var/lib/dovecot/sieve || true
  fi
fi

mkdir -p /var/mail/vhosts
if [ "$have_vmail" = "true" ]; then
  chown -R vmail:vmail /var/mail
fi
find /var/mail -type d -exec chmod 2775 {} \;
find /var/mail -type f -exec chmod 0664 {} \;
