# Cyrus IMAP mailbox server for the Robin suite.
# Mail is delivered by Robin over LMTP. IMAP clients connect through the suite's
# nginx mail proxy, which resolves aliases with Robin before logging in here.
# Passwords are verified against Robin's users API via saslauthd and PAM.
FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        cyrus-admin \
        cyrus-clients \
        cyrus-imapd \
        jq \
        netcat-openbsd \
        rsyslog \
        sasl2-bin \
    && rm -rf /var/lib/apt/lists/*

COPY entrypoint.sh /usr/local/bin/suite-cyrus-entrypoint
COPY robin-auth.sh /usr/local/bin/robin-auth
COPY healthcheck.sh /usr/local/bin/suite-cyrus-healthcheck

RUN chmod 755 /usr/local/bin/suite-cyrus-entrypoint /usr/local/bin/robin-auth /usr/local/bin/suite-cyrus-healthcheck

ENV PATH="/usr/lib/cyrus/bin:${PATH}"

EXPOSE 24 143 4190

CMD ["/usr/local/bin/suite-cyrus-entrypoint"]
