# Dockerfile for Haraka + Dovecot LDA Testing
# Single container with Haraka SMTP, Dovecot IMAP/LDA, and Supervisor.

FROM node:20-bookworm-slim AS haraka-build

RUN npm install -g Haraka

FROM ubuntu:24.04

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
    supervisor \
    netcat-openbsd \
    curl \
    gnupg \
    openssl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Add Dovecot official repository for version 2.4.
RUN curl https://repo.dovecot.org/DOVECOT-REPO-GPG-2.4 | gpg --dearmor -o /usr/share/keyrings/dovecot.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/dovecot.gpg] https://repo.dovecot.org/ce-2.4-latest/ubuntu/noble noble main" \
    > /etc/apt/sources.list.d/dovecot.list \
    && apt-get update \
    && apt-get install -y \
    dovecot-core \
    dovecot-imapd \
    dovecot-pop3d \
    dovecot-lmtpd \
    dovecot-pgsql \
    && rm -rf /var/lib/apt/lists/*

# Create vmail user for LDA delivery.
RUN groupadd -g 5000 vmail && \
    useradd -u 5000 -g vmail -s /usr/sbin/nologin -d /var/mail vmail

RUN mkdir -p /var/mail/vhosts /var/lib/dovecot /run/dovecot /opt/haraka \
    && chown -R vmail:vmail /var/mail \
    && chown -R dovecot:dovecot /var/lib/dovecot /run/dovecot

COPY --from=haraka-build /usr/local/bin/node /usr/local/bin/node
COPY --from=haraka-build /usr/local/bin/haraka /usr/local/bin/haraka
COPY --from=haraka-build /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -sf /usr/local/lib/node_modules/Haraka/bin/haraka /usr/local/bin/haraka \
    && haraka -i /opt/haraka \
    && openssl req -x509 -nodes -days 3650 -newkey rsa:2048 \
        -subj "/CN=perf-haraka-lda" \
        -keyout /opt/haraka/config/tls_key.pem \
        -out /opt/haraka/config/tls_cert.pem

COPY .perf/robin-dovecot-lda/supervisord-haraka.conf /etc/supervisor/conf.d/supervisord.conf
COPY .perf/robin-dovecot-lda/docker-init.sh /usr/local/bin/docker-init.sh
COPY .perf/robin-dovecot-lda/quota-warning.sh /usr/local/bin/quota-warning.sh
COPY .perf/robin-dovecot-lda/haraka/config /opt/haraka/config
COPY .perf/robin-dovecot-lda/haraka/plugins /opt/haraka/plugins
RUN chmod +x /usr/local/bin/docker-init.sh /usr/local/bin/quota-warning.sh

WORKDIR /opt/haraka

EXPOSE 25 110 143 993 4190

CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf", "-n"]
