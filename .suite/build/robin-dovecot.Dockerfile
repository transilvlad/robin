# Optimized Dockerfile for combined Robin SMTP + Dovecot IMAP/POP3
# Uses multi-stage build with shared stages that can be reused by standalone Dockerfiles
# For development/testing of Robin integration with Dovecot's UNIX sockets and LDA
# See .suite/etc/dovecot/conf.d/10-auth.dovecot.conf for auth details

# ============================================================================
# STAGE: robin-build
# Shared Maven build stage (can be referenced by standalone Robin Dockerfile)
# ============================================================================
FROM maven:3.9.9-amazoncorretto-21-debian AS robin-build

WORKDIR /usr/src/robin
COPY pom.xml .

# Download dependencies with cache mount
RUN --mount=type=cache,target=/root/.m2,id=robin-m2 \
    --mount=type=cache,target=/root/.cache,id=robin-cache \
    mvn -B -q -e dependency:go-offline

# Build project
COPY src ./src
RUN --mount=type=cache,target=/root/.m2,id=robin-m2 \
    --mount=type=cache,target=/root/.cache,id=robin-cache \
    mvn -B -q clean package -Dmaven.test.skip=true

# ============================================================================
# STAGE: production
# Combined Robin + Dovecot in single Alpine Java container
# ============================================================================
FROM alpine/java:21-jdk AS production

# Copy scripts and configs
COPY .suite/build/supervisord.conf /etc/supervisord.conf
COPY .suite/build/docker-init.sh /usr/local/bin/docker-init.sh
COPY .suite/build/quota-warning.sh /usr/local/bin/quota-warning.sh

# Install all packages in one layer (supervisor + Dovecot stack)
# Using Alpine's latest Dovecot package (2.3.x series, preparing for 2.4 migration)
RUN apk update && apk add --no-cache \
    supervisor \
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

# Create vmail user and setup Dovecot directories
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

# Copy Robin artifacts from build stage
COPY --from=robin-build /usr/src/robin/target/classes/lib /usr/local/robin/lib
COPY --from=robin-build /usr/src/robin/target/robin.jar /usr/local/robin/robin.jar
COPY src/test/resources/keystore.jks /usr/local/robin/keystore.jks

# Conditional config inclusion
ARG INCLUDE_CFG=true
COPY cfg /tmp/cfg/
RUN if [ "$INCLUDE_CFG" = "true" ]; then \
      mv /tmp/cfg /usr/local/robin/cfg; \
    else \
      rm -rf /tmp/cfg && mkdir -p /usr/local/robin/cfg; \
    fi

# Expose all service ports
EXPOSE 25 465 587 110 143 993 4190 8080 8090

# Run supervisord to manage both services
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisord.conf", "-n"]
