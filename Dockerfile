# Build
FROM maven:3.9.9-amazoncorretto-21-debian AS build

COPY ./ /usr/src/robin/

WORKDIR /usr/src/robin/

RUN mvn clean compile assembly:single



# Production
FROM amazoncorretto:21 AS production

COPY --from=build /usr/src/robin/target/robin-jar-with-dependencies.jar /usr/local/robin/bin/robin.jar
# COPY ./cfg /usr/local/robin/cfg # Needed when not using Docker compose
COPY ./src/test/resources/keystore.jks /usr/local/robin/keystore.jks

WORKDIR /usr/local/robin/

# Create storage folders
RUN \
    mkdir -p /usr/local/robin/log \
    && \
      mkdir -p /usr/local/robin/store

EXPOSE 25

# Run the service when the container launches
CMD ["/bin/bash", "-c", "java -server -Xms256m -Xmx1024m -Dlog4j.configurationFile=/usr/local/robin/cfg/log4j2.xml -jar /usr/local/robin/bin/robin.jar --server /usr/local/robin/cfg"]
