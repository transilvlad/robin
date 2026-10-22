#!/bin/sh
# Healthy when saslauthd runs and IMAP and LMTP answer with a greeting.
set -eu

kill -0 "$(cat /run/saslauthd/saslauthd.pid)"
printf 'a1 LOGOUT\r\n' | nc -w 5 127.0.0.1 143 | grep -q '^\* OK'
printf 'QUIT\r\n' | nc -w 5 127.0.0.1 24 | grep -q '^220 '
