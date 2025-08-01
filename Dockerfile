# Build stage
FROM maven:3.9.9-amazoncorretto-21-debian AS build

WORKDIR /usr/src/robin

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

# Production stage
FROM amazoncorretto:21 AS production

COPY --from=build /usr/src/robin/target/robin-jar-with-dependencies.jar /usr/local/robin/bin/robin.jar
COPY ./src/test/resources/keystore.jks /usr/local/robin/keystore.jks
# COPY cfg /usr/local/robin/cfg # Uncomment if not using Docker Compose

EXPOSE 25

CMD ["/bin/bash", "-c", "java -server -Xms256m -Xmx1024m -Dlog4j.configurationFile=/usr/local/robin/cfg/log4j2.xml -jar /usr/local/robin/bin/robin.jar --server /usr/local/robin/cfg/"]