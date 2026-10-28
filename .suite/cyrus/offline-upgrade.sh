#!/bin/sh
# Invoked only by upgrade.py on its labelled restored clone, with no network.
set -eu
umask 077
data=/var/lib/cyrus
stage="${CYRUS_UPGRADE_STAGE:?Missing reviewed upgrade stage}"
previous="$(cat "$data/.robin-jmap-upgraded" 2>/dev/null || true)"
case "$stage:$previous" in
    3.8.8:|3.10.4:3.8.8|3.12.4:3.10.4) ;;
    *) echo 'ERROR: Invalid upgrade sequence; restore a new clone before retrying' >&2; exit 1 ;;
esac
if [ -n "$previous" ] && [ "$(cat "$data/.robin-jmap-verified" 2>/dev/null || true)" != "$previous" ]; then
    echo 'ERROR: Verify the previous running stage before continuing' >&2
    exit 1
fi
mkdir -p /run/cyrus/proc /run/cyrus/lock
cp /etc/imapd-base.conf /run/cyrus/imapd.conf
if [ "$stage" = 3.12.4 ]; then
    cat /etc/imapd-jmap.conf >> /run/cyrus/imapd.conf
    mkdir -p "$data/search" "$data/config/sync/squatter"
    chown cyrus:mail "$data/search" "$data/config/sync" "$data/config/sync/squatter"
fi
chown cyrus:mail /run/cyrus /run/cyrus/proc /run/cyrus/lock /run/cyrus/imapd.conf
runuser -u cyrus -- ctl_cyrusdb -C /run/cyrus/imapd.conf -r
runuser -u cyrus -- reconstruct -C /run/cyrus/imapd.conf -V max
runuser -u cyrus -- quota -C /run/cyrus/imapd.conf -f
if [ "$stage" = 3.12.4 ]; then
    runuser -u cyrus -- ctl_conversationsdb -C /run/cyrus/imapd.conf -b -r
    runuser -u cyrus -- squatter -C /run/cyrus/imapd.conf
fi
runuser -u cyrus -- ctl_cyrusdb -C /run/cyrus/imapd.conf -c
printf '%s\n' "$stage" > "$data/.robin-jmap-upgraded"
echo "PASS offline Cyrus $stage; daemon/protocol verification still required"
