#!/bin/sh
set -eu
kill -0 "$(cat /run/saslauthd/saslauthd.pid)"
printf 'a1 LOGOUT\r\n' | nc -w 5 127.0.0.1 143 | grep -q '^\* OK'
printf 'QUIT\r\n' | nc -w 5 127.0.0.1 24 | grep -q '^220 '
printf 'LOGOUT\r\n' | nc -w 5 127.0.0.1 4190 | grep -q '^"IMPLEMENTATION"'
[ "$(curl --silent --output /dev/null --max-time 5 --write-out '%{http_code}' http://127.0.0.1:8008/jmap/)" = 401 ]
