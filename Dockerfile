# Multi-stage build: compile with Maven, run with JRE only.
# If Docker Hub / Maven Central are slow, set build-args for proxy:
#   docker compose build --build-arg HTTP_PROXY=http://host.docker.internal:7890 ...

ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY=localhost,127.0.0.1

FROM maven:3.9-eclipse-temurin-17 AS build
ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY
ENV http_proxy=${HTTP_PROXY} \
    https_proxy=${HTTPS_PROXY} \
    HTTP_PROXY=${HTTP_PROXY} \
    HTTPS_PROXY=${HTTPS_PROXY} \
    NO_PROXY=${NO_PROXY} \
    no_proxy=${NO_PROXY}

WORKDIR /app
COPY pom.xml .
# Prefetch dependencies for better layer caching
RUN mvn -B -q dependency:go-offline || mvn -B -q dependency:resolve
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

# Runtime data & report directories (also mounted via compose)
RUN mkdir -p /app/data/generated /app/reports

COPY --from=build /app/target/play-duckdb-1.0.0.jar /app/app.jar
COPY sql /app/sql

# Default: run the full learning pipeline (seed → query → report)
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["all"]
