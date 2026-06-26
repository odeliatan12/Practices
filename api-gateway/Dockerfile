# ─── Stage 1: Build ──────────────────────────────────────────────────────────
# Build from project root: docker build -f api-gateway/Dockerfile .
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /workspace

# Copy ALL module pom.xml files — parent POM references every module
COPY pom.xml ./pom.xml
COPY shared-lib/pom.xml            ./shared-lib/pom.xml
COPY api-gateway/pom.xml           ./api-gateway/pom.xml
COPY order-service/pom.xml         ./order-service/pom.xml
COPY payment-service/pom.xml       ./payment-service/pom.xml
COPY fulfillment-service/pom.xml   ./fulfillment-service/pom.xml
COPY inventory-service/pom.xml     ./inventory-service/pom.xml
COPY notification-service/pom.xml  ./notification-service/pom.xml
COPY user-service/pom.xml          ./user-service/pom.xml
COPY search-indexer/pom.xml        ./search-indexer/pom.xml

RUN mvn dependency:go-offline -pl api-gateway -am -B -q 2>/dev/null || true

# Copy source
COPY shared-lib/src ./shared-lib/src
COPY api-gateway/src ./api-gateway/src

RUN mvn package -pl api-gateway -am -DskipTests -B -q

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app

COPY --from=builder /workspace/api-gateway/target/api-gateway-*.jar app.jar

EXPOSE 8090

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
