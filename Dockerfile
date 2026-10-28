# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# Runtime stage: JRE only, non-root user
FROM eclipse-temurin:21-jre-alpine
# Apply OS security patches released after the base image was built.
RUN apk upgrade --no-cache && addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/target/ledgerforge-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
