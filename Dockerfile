# Build stage
FROM maven:3.9.9-amazoncorretto-21-debian AS build

# Set the working directory inside the container.
WORKDIR /usr/src/robin

# Copy the project files to the working directory.
COPY pom.xml .
COPY src ./src

# Build the project.
RUN mvn clean package -Dmaven.test.skip=true

# Production stage
FROM alpine/java:21-jdk AS production

# Copy supervisord configuration files.
COPY supervisord.conf /etc/supervisord.conf
COPY docker-init.sh /usr/local/bin/docker-init.sh

# Dovecot application setup.

# Update the package index and then install Dovecot.
RUN apk update && apk add --no-cache \
    supervisor \
    dovecot \
    dovecot-lmtpd \
    dovecot-pigeonhole-plugin \
    bash \
    socat

# Create vmail user and group with fixed UID/GID
RUN addgroup -g 5000 -S vmail && \
    adduser  -u 5000 -S -D -h /var/mail -G vmail vmail

# Create the run directory and set the correct ownership for the dovecot user.
RUN mkdir -p /run/dovecot \
    && chown vmail:vmail /run/dovecot \
    && mkdir -p /var/mail/vhosts \
    && chown -R vmail:vmail /var/mail/vhosts \
    && mkdir -p /etc/dovecot/conf.d \
    && chown -R vmail:vmail /etc/dovecot

# Robin application setup.

# Copy the built JAR file from the build stage to the production stage.
COPY --from=build /usr/src/robin/target/robin-jar-with-dependencies.jar /usr/local/robin/bin/robin.jar

# Copy the keystore file to the appropriate location.
COPY ./src/test/resources/keystore.jks /usr/local/robin/keystore.jks

# Copy configuration files to the cfg directory.
# COPY cfg /usr/local/robin/cfg # Uncomment if not using Docker Compose.

# --

# Expose standard SMTP & IMAP ports and Robin endpoints port.
EXPOSE 25 465 587 143 993 8080 8090

# Run supervisor in foreground.
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisord.conf", "-n"]