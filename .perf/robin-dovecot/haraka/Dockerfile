FROM node:20-bookworm-slim

RUN apt-get update && apt-get install -y \
    netcat-openbsd \
    openssl \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g Haraka \
    && haraka -i /opt/haraka \
    && openssl req -x509 -nodes -days 3650 -newkey rsa:2048 \
        -subj "/CN=perf-haraka" \
        -keyout /opt/haraka/config/tls_key.pem \
        -out /opt/haraka/config/tls_cert.pem

COPY .perf/robin-dovecot/haraka/config /opt/haraka/config

WORKDIR /opt/haraka

EXPOSE 25

CMD ["haraka", "-c", "/opt/haraka"]
