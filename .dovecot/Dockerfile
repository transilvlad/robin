# Build stage
FROM maven:3.9.9-amazoncorretto-21-debian AS build

# Set the working directory inside the container.
WORKDIR /usr/src/robin

# Copy the POM to the working directory.
COPY ../pom.xml .

# Resolve and download all dependencies (including plugins) using BuildKit cache mounts.
RUN --mount=type=cache,target=/root/.m2,id=robin-m2 \
    --mount=type=cache,target=/root/.cache,id=robin-cache \
    mvn -B -q -e dependency:go-offline

# Now copy the source; changes here won't bust the dependency cache.
COPY ../src ./src

# Build the project using the same cached Maven repo.
RUN --mount=type=cache,target=/root/.m2,id=robin-m2 \
    --mount=type=cache,target=/root/.cache,id=robin-cache \
    mvn -B -q clean package -Dmaven.test.skip=true

# Production stage
FROM alpine/java:21-jdk AS production

# Copy supervisord configuration files.
COPY .dovecot/supervisord.conf /etc/supervisord.conf
COPY .dovecot/docker-init.sh /usr/local/bin/docker-init.sh

# Dovecot application setup.

# Update the package index and then install Dovecot, plugins and utils.
RUN apk update && apk add --no-cache \
    supervisor \
    dovecot \
    dovecot-lmtpd \
    dovecot-pigeonhole-plugin \
    dovecot-fts-lucene \
    dovecot-pop3d \
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

# Robin application setup.

# Copy dependencies lib/ folder and the built JAR file from the build stage to the production stage.
COPY --from=build /usr/src/robin/target/classes/lib /usr/local/robin/lib
COPY --from=build /usr/src/robin/target/robin.jar /usr/local/robin/robin.jar

# Copy the keystore file to the appropriate location.
COPY src/test/resources/keystore.jks /usr/local/robin/keystore.jks

# Build argument to control whether to include cfg files in the image
# Default is true for published standalone images
# Set to false when building with docker-compose: --build-arg INCLUDE_CFG=false
ARG INCLUDE_CFG=true

# Conditionally copy configuration files
RUN if [ "$INCLUDE_CFG" = "true" ]; then mkdir -p /tmp/include-cfg; fi
COPY cfg /tmp/cfg-temp/
RUN if [ "$INCLUDE_CFG" = "true" ]; then \
      mv /tmp/cfg-temp /usr/local/robin/cfg; \
    else \
      rm -rf /tmp/cfg-temp && mkdir -p /usr/local/robin/cfg; \
    fi

# --

# Expose standard SMTP & IMAP/POP3 & ManageSieve ports and Robin endpoint ports.
EXPOSE 25 465 587 110 143 993 4190 8080 8090

# Run supervisor in foreground.
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisord.conf", "-n"]
