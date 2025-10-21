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

# Copy dependencies lib/ folder and the built JAR file from the build stage to the production stage.
COPY --from=build /usr/src/robin/target/classes/lib /usr/local/robin/lib
COPY --from=build /usr/src/robin/target/robin.jar /usr/local/robin/robin.jar

# Copy the keystore file to the appropriate location.
COPY ./src/test/resources/keystore.jks /usr/local/robin/keystore.jks

# Copy configuration files to the cfg directory.
# COPY cfg /usr/local/robin/cfg # Uncomment if not using Docker Compose

# Expose standard SMTP ports and Robin endpoint ports.
EXPOSE 25 465 587 8080 8090

# Run supervisor in foreground.
CMD java -server -Xms256m -Xmx1024m -Dlog4j.configurationFile=/usr/local/robin/cfg/log4j2.xml -cp "/usr/local/robin/robin.jar:/usr/local/robin/lib/*" com.mimecast.robin.Main --server /usr/local/robin/cfg/
