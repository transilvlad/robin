#!/usr/bin/env bash
set -euo pipefail

mkdir -p /var/mail/vhosts
chown -R vmail:vmail /var/mail
find /var/mail -type d -exec chmod 2775 {} \;
find /var/mail -type f -exec chmod 0664 {} \;
