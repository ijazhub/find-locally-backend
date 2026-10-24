# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package \
    && cp target/api-*.jar /build/app.jar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build --chown=app:app /build/app.jar /app/app.jar
USER app:app
ENV SERVER_PORT=8080 \
    SERVER_FORWARD_HEADERS_STRATEGY=framework \
    SERVER_SHUTDOWN=graceful
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
