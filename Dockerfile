# syntax=docker/dockerfile:1.7
# ---- 1. Frontend build ----
FROM node:22-alpine AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---- 2. Backend build (bundles the frontend as static resources) ----
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /src/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY backend/src src
COPY --from=frontend /src/frontend/dist src/main/resources/static
# Tests need PostgreSQL and run in CI; the image build only packages.
RUN ./mvnw -q -B -DskipTests package && cp target/relay-backend-*.jar /app.jar

# ---- 3. Runtime ----
FROM eclipse-temurin:21-jre
RUN groupadd --system relay && useradd --system --gid relay --home /app relay \
    && apt-get update && apt-get install -y --no-install-recommends curl \
    && apt-get clean && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=backend /app.jar /app/relay.jar
USER relay
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1
# exec form so SIGTERM reaches the JVM and graceful shutdown runs
ENTRYPOINT ["java", "-jar", "/app/relay.jar"]
