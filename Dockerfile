# Optimized Dockerfile for standalone Robin MTA
# Uses same stage naming as robin-dovecot for potential build cache sharing
# Build stage name 'robin-build' matches .Dockerfile

# ============================================================================
# STAGE: robin-build
# Maven build stage (shared name with robin-dovecot for cache reuse)
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

# Keep development fixtures out of production image layers when omitted.
FROM robin-build AS robin-config
ARG INCLUDE_CFG=true
COPY cfg /tmp/cfg/
RUN mkdir -p /runtime/cfg && \
    if [ "$INCLUDE_CFG" = "true" ]; then \
      cp -R /tmp/cfg/. /runtime/cfg/; \
      cp /usr/src/robin/src/test/resources/keystore.jks /runtime/keystore.jks; \
    fi

# ============================================================================
# STAGE: production
# Standalone Robin MTA without Dovecot
# ============================================================================
FROM amazoncorretto:21-alpine AS production

# Copy Robin artifacts from build stage
COPY --from=robin-build /usr/src/robin/target/classes/lib /usr/local/robin/lib
COPY --from=robin-build /usr/src/robin/target/robin.jar /usr/local/robin/robin.jar
COPY --from=robin-config /runtime/ /usr/local/robin/

# Expose SMTP and API ports
EXPOSE 25 465 587 8080 8090

# Run Robin MTA
CMD java -server -Xms256m -Xmx1024m \
    -Dlog4j.configurationFile=/usr/local/robin/cfg/log4j2.xml \
    -cp "/usr/local/robin/robin.jar:/usr/local/robin/lib/*" \
    com.mimecast.robin.Main --server /usr/local/robin/cfg/
