# Optimized Dockerfile for standalone Dovecot IMAP/POP3 server
# Used in the full suite for separate Dovecot container
# Follows same optimization pattern as robin-dovecot for consistency

FROM alpine:latest

# Copy initialization scripts
COPY .suite/build/docker-init.sh /usr/local/bin/docker-init.sh
COPY .suite/build/quota-warning.sh /usr/local/bin/quota-warning.sh

# Install Dovecot and plugins in single layer
# Using Alpine's latest Dovecot package (2.3.x series, preparing for 2.4 migration)
RUN apk update && apk add --no-cache \
    dovecot \
    dovecot-lmtpd \
    dovecot-pigeonhole-plugin \
    dovecot-fts-lucene \
    dovecot-pop3d \
    dovecot-pgsql \
    bash \
    socat \
    openssl \
    && rm -rf /var/cache/apk/*

# Create vmail user and setup all directories in one layer
RUN addgroup -g 5000 -S vmail \
    && mkdir -p /var/mail/vhosts/example.com/_shared \
    && adduser -u 5000 -S -D -h /var/mail -G vmail vmail \
    && mkdir -p /run/dovecot \
    && chown vmail:vmail /run/dovecot \
    && mkdir -p /var/mail/attachments \
    && mkdir -p /var/mail/vhosts \
    && chown -R vmail:vmail /var/mail \
    && mkdir -p /etc/dovecot/conf.d \
    && chown -R vmail:vmail /etc/dovecot \
    && chmod +x /usr/local/bin/docker-init.sh \
    && chmod +x /usr/local/bin/quota-warning.sh

# Expose IMAP/POP3/LMTP ports
EXPOSE 24 110 143 993 4190

# Run Dovecot in foreground
CMD ["/bin/sh", "-c", "/usr/local/bin/docker-init.sh && exec /usr/sbin/dovecot -F"]
