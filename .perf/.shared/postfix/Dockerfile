FROM debian:bookworm-slim

# Install Postfix and PostgreSQL client
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
    postfix \
    postfix-pgsql \
    postgresql-client \
    netcat-openbsd \
    rsyslog \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# Configure Postfix for non-interactive mode
RUN postconf -e "inet_interfaces=all" && \
    postconf -e "inet_protocols=ipv4" && \
    postconf -e "maillog_file=/var/log/postfix/maillog"

# Create log directory
RUN mkdir -p /var/log/postfix

# Expose SMTP port
EXPOSE 25

# Start Postfix in foreground (without rsyslog - Postfix logs directly via maillog_file)
CMD ["postfix", "start-fg"]
