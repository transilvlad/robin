# Integration test runner for the Robin suite.
# Pins Maven and JDK so tests run the same on any machine; sources are mounted at /src.
FROM maven:3.9.9-amazoncorretto-21-debian

RUN apt-get update \
    && apt-get install -y --no-install-recommends socat netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*

COPY run-tests.sh /usr/local/bin/run-tests.sh
RUN chmod +x /usr/local/bin/run-tests.sh

ENTRYPOINT ["/usr/local/bin/run-tests.sh"]
