#!/bin/sh
set -eu
umask 077
data=/var/lib/cyrus
config=/etc/imapd.conf
release="${CYRUS_RELEASE:?Missing built Cyrus version}"

mkdir -p /run/cyrus/proc /run/cyrus/lock /run/saslauthd
cat /etc/imapd-base.conf /etc/imapd-jmap.conf > "$config"
# An arbitrary Host must not influence discovery. The TLS proxy sends this name.
case "${JMAP_SERVER_NAME:-jmap-backend}" in
    *[!a-zA-Z0-9.-]*|'') echo 'ERROR: Invalid JMAP_SERVER_NAME' >&2; exit 1 ;;
esac
printf '\nservername: %s\n' "${JMAP_SERVER_NAME:-jmap-backend}" >> "$config"

password=
IFS= read -r password < /run/secrets/cyrus_admin_password || [ -n "$password" ]
[ "${#password}" -ge 32 ] || { echo 'ERROR: Cyrus admin secret is too short' >&2; exit 1; }
printf '%s' "$password" | saslpasswd2 -p -c -u "${JMAP_SERVER_NAME:-jmap-backend}" cyrus
unset password
chown cyrus:mail /etc/sasldb2
chmod 640 /etc/sasldb2

auth_url="${ROBIN_AUTH_URL:-http://mta-backend:8090/users/authenticate}"
case "$auth_url" in
    http://mta-backend:8090/users/authenticate) ;;
    *) echo 'ERROR: Unexpected Robin authentication endpoint' >&2; exit 1 ;;
esac
printf 'auth required pam_exec.so expose_authtok quiet /usr/local/bin/robin-auth %s\naccount required pam_permit.so\n' "$auth_url" > /etc/pam.d/robin-mailbox
for service in imap sieve pop http; do
    printf '@include robin-mailbox\n' > "/etc/pam.d/$service"
done

# Never reconstruct or recursively chown a live legacy store at startup.
mkdir -p "$data"
if [ -n "$(find "$data" -type f -print -quit)" ] && [ "$(cat "$data/.robin-jmap-upgraded" 2>/dev/null || true)" != "$release" ]; then
    echo 'ERROR: Existing Cyrus data requires the offline clone/rehearsal upgrade first' >&2
    exit 1
fi
mkdir -p "$data/config" "$data/mail" "$data/sieve" "$data/search"
chown cyrus:mail "$data" "$data/config" "$data/mail" "$data/sieve" "$data/search" /run/cyrus /run/cyrus/proc /run/cyrus/lock "$config"
chmod 750 "$data"
mkdir -p "$data/config/db" "$data/config/socket" "$data/config/log" "$data/config/msg" "$data/config/quota" "$data/config/user" "$data/config/sync/squatter"
chown cyrus:mail "$data/config/"*
printf '%s\n' "$release" > "$data/.robin-jmap-upgraded"
printf 'module(load="imuxsock")\n$FileCreateMode 0640\n*.* /var/log/cyrus.log\n' > /etc/rsyslog.conf
rm -f /run/rsyslogd.pid /run/saslauthd/saslauthd.pid
rsyslogd
saslauthd -a pam -r -m /run/saslauthd -n 5
exec master -l 64 -C "$config" -M /etc/cyrus-jmap.conf
