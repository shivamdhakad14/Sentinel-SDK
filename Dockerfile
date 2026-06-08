# ─── Stage 1: Build ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

# Cache Maven dependencies first
COPY pom.xml .
RUN apk add --no-cache maven && mvn dependency:go-offline -q

# Build the jar
COPY src ./src
RUN mvn clean package -DskipTests -q

# ─── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN apk add --no-cache curl

# Security: non-root user
RUN addgroup -S sentinel && adduser -S sentinel -G sentinel
USER sentinel

# Copy the fat jar
COPY --from=builder /build/target/sentinel-sdk-*.jar app.jar

# Generated tests output volume
VOLUME /app/generated-tests

EXPOSE 8090

# JVM tuning for containers
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-default} -jar app.jar"]
