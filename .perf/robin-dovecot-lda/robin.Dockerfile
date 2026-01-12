# Dockerfile for Robin MTA + Dovecot LDA Testing
# Starts from official Dovecot image and adds Java 21 + Robin for LDA integration testing
# Uses supervisor to run both services in a single container

# ============================================================================
# STAGE: robin-build
# Build Robin MTA
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
# Ubuntu + Java 21 + Dovecot 2.4 + Robin (combined container for LDA testing)
# ============================================================================
FROM ubuntu:24.04 AS production

# Install Java 21, supervisor, curl, and gnupg first
RUN apt-get update && apt-get install -y \
    openjdk-21-jre-headless \
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

# Create necessary directories (dovecot user already created by package)
RUN mkdir -p /var/mail/vhosts /var/lib/dovecot /run/dovecot \
    && chown -R dovecot:dovecot /var/mail /var/lib/dovecot /run/dovecot

# Copy initialization scripts
COPY .perf/robin-dovecot-lda/supervisord-robin.conf /etc/supervisor/conf.d/supervisord.conf
COPY .perf/robin-dovecot-lda/docker-init.sh /usr/local/bin/docker-init.sh
COPY .perf/robin-dovecot-lda/quota-warning.sh /usr/local/bin/quota-warning.sh
RUN chmod +x /usr/local/bin/docker-init.sh /usr/local/bin/quota-warning.sh

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
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf", "-n"]
