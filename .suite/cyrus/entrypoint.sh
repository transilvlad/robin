#!/bin/sh
set -eu

CYRUS_ADMIN_PASSWORD=
IFS= read -r CYRUS_ADMIN_PASSWORD < /run/secrets/cyrus_admin_password || [ -n "$CYRUS_ADMIN_PASSWORD" ]
if [ "${#CYRUS_ADMIN_PASSWORD}" -lt 32 ]; then
    echo "ERROR: Cyrus admin password must contain at least 32 characters" >&2
    exit 1
fi

ROBIN_AUTH_URL="${ROBIN_AUTH_URL:-http://mta-backend:8090/users/authenticate}"
DATA_DIR=/var/lib/cyrus

# Syslog to a file under /var/log (Cyrus only logs to syslog).
cat > /etc/rsyslog.conf <<'EOF'
module(load="imuxsock")
$FileCreateMode 0644
*.* /var/log/cyrus.log
EOF
rm -f /run/rsyslogd.pid
rsyslogd

# Mailbox logins are verified by Robin; the global admin lives in sasldb.
cat > /etc/pam.d/robin-mailbox <<EOF
auth    required    pam_exec.so expose_authtok quiet /usr/local/bin/robin-auth $ROBIN_AUTH_URL
account required    pam_permit.so
EOF
for service in imap sieve pop; do
    printf '@include robin-mailbox\n' > "/etc/pam.d/$service"
done

rm -f /etc/sasldb2
printf '%s' "$CYRUS_ADMIN_PASSWORD" | saslpasswd2 -p -c cyrus
chown cyrus:mail /etc/sasldb2
chmod 640 /etc/sasldb2
unset CYRUS_ADMIN_PASSWORD

mkdir -p "$DATA_DIR/config" "$DATA_DIR/mail" "$DATA_DIR/sieve" /run/cyrus/proc /run/cyrus/lock /run/saslauthd
if [ "$(stat -c %U "$DATA_DIR/config")" != cyrus ]; then
    chown -R cyrus:mail "$DATA_DIR"
fi
chown cyrus:mail "$DATA_DIR" /run/cyrus /run/cyrus/proc /run/cyrus/lock
chmod 750 "$DATA_DIR"
/usr/sbin/cyrus makedirs

rm -f /run/saslauthd/saslauthd.pid
saslauthd -a pam -r -m /run/saslauthd -n 5

exec /usr/sbin/cyrmaster -l 64 -C /etc/imapd.conf -M /etc/cyrus.conf
