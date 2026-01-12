# Dockerfile for Postfix + Dovecot LDA Testing
# Single container with Postfix + Dovecot + Supervisor for LDA integration testing
# Uses LDA (Local Delivery Agent) for subprocess-based delivery

FROM ubuntu:24.04

# Install Postfix, Supervisor, curl, and gnupg first
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
    postfix \
    postfix-pgsql \
    postgresql-client \
    supervisor \
    netcat-openbsd \
    curl \
    gnupg \
    && rm -rf /var/lib/apt/lists/*

# Add Dovecot official repository for version 2.4
RUN curl https://repo.dovecot.org/DOVECOT-REPO-GPG-2.4 | gpg --dearmor -o /usr/share/keyrings/dovecot.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/dovecot.gpg] https://repo.dovecot.org/ce-2.4-latest/ubuntu/noble noble main" \
    > /etc/apt/sources.list.d/dovecot.list

# Install Dovecot 2.4 from official repository
RUN apt-get update && apt-get install -y \
    dovecot-core \
    dovecot-imapd \
    dovecot-pop3d \
    dovecot-lmtpd \
    dovecot-pgsql \
    && rm -rf /var/lib/apt/lists/*

# Create vmail user for LDA delivery
RUN groupadd -g 5000 vmail && \
    useradd -u 5000 -g vmail -s /usr/sbin/nologin -d /var/mail vmail

# Create necessary directories (dovecot user already created by package)
RUN mkdir -p /var/mail/vhosts /var/lib/dovecot /run/dovecot /var/log/postfix && \
    chown -R vmail:vmail /var/mail && \
    chown -R dovecot:dovecot /var/lib/dovecot /run/dovecot

# Configure Postfix
RUN postconf -e "inet_interfaces=all" && \
    postconf -e "inet_protocols=ipv4" && \
    postconf -e "maillog_file=/var/log/postfix/maillog"

# Copy supervisor config and init scripts
COPY .perf/robin-dovecot-lda/supervisord-postfix.conf /etc/supervisor/conf.d/supervisord.conf
COPY .perf/robin-dovecot-lda/docker-init.sh /usr/local/bin/docker-init.sh
COPY .perf/robin-dovecot-lda/quota-warning.sh /usr/local/bin/quota-warning.sh
RUN chmod +x /usr/local/bin/docker-init.sh /usr/local/bin/quota-warning.sh

# Expose all service ports
EXPOSE 25 110 143 993 4190

# Run supervisord to manage both services
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf", "-n"]
