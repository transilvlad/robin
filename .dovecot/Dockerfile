# This is the Dockerfile for the stand-alone Dovecot IMAP/POP3 server used in the full suite.

FROM alpine:latest

# Copy docker-init script.
COPY .dovecot/docker-init.sh /usr/local/bin/docker-init.sh

# Update the package index and then install Dovecot, plugins and utils.
RUN apk update && apk add --no-cache \
    dovecot \
    dovecot-lmtpd \
    dovecot-pigeonhole-plugin \
    dovecot-pop3d \
    dovecot-pgsql \
    bash \
    socat \
    openssl

# Create vmail user and group with fixed UID/GID
RUN addgroup -g 5000 -S vmail \
    && mkdir -p /var/mail/vhosts/example.com/_shared \
    && adduser  -u 5000 -S -D -h /var/mail -G vmail vmail

# Copy quota warning script.
COPY .dovecot/quota-warning.sh /usr/local/bin/quota-warning.sh

# Prepare mail storage (including attachment de-dup directory) and Dovecot runtime dirs.
RUN mkdir -p /run/dovecot \
    && chown vmail:vmail /run/dovecot \
    && mkdir -p /var/mail/attachments \
    && mkdir -p /var/mail/vhosts \
    && chown -R vmail:vmail /var/mail \
    && mkdir -p /etc/dovecot/conf.d \
    && chown -R vmail:vmail /etc/dovecot \
    && chmod +x /usr/local/bin/docker-init.sh \
    && chmod +x /usr/local/bin/quota-warning.sh

# Expose standard IMAP/POP3 & LMTP ports.
EXPOSE 24 110 143 993 4190

# Run init script then start Dovecot in foreground.
CMD ["/bin/bash", "-c", "/usr/local/bin/docker-init.sh && exec /usr/sbin/dovecot -F"]
