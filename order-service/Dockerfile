# ─── Stage 1: Build ──────────────────────────────────────────────────────────
# Build from project root: docker build -f order-service/Dockerfile .
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /workspace

# Copy ALL module pom.xml files first — the parent pom.xml references every module,
# so Maven needs their directories to exist even for a targeted (-pl) build.
# Copying only POMs (not source) keeps this layer cached when only source changes.
COPY pom.xml ./pom.xml
COPY shared-lib/pom.xml            ./shared-lib/pom.xml
COPY order-service/pom.xml         ./order-service/pom.xml
COPY api-gateway/pom.xml           ./api-gateway/pom.xml
COPY payment-service/pom.xml       ./payment-service/pom.xml
COPY fulfillment-service/pom.xml   ./fulfillment-service/pom.xml
COPY inventory-service/pom.xml     ./inventory-service/pom.xml
COPY notification-service/pom.xml  ./notification-service/pom.xml
COPY user-service/pom.xml          ./user-service/pom.xml
COPY search-indexer/pom.xml        ./search-indexer/pom.xml

# Download all dependencies before copying source — maximises layer cache reuse.
# -B = batch mode (no progress bars in CI), -q = quiet
RUN mvn dependency:go-offline -pl order-service -am -B -q 2>/dev/null || true

# Copy source code for shared-lib and order-service
COPY shared-lib/src ./shared-lib/src
COPY order-service/src ./order-service/src

# Build the fat JAR (skip tests — tests run in CI before docker build)
RUN mvn package -pl order-service -am -DskipTests -B -q

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
# JRE only — no JDK tools needed at runtime, keeps image size small
FROM eclipse-temurin:21-jre-alpine AS runtime

# Run as non-root — security best practice
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app

# Copy only the fat JAR from the build stage — nothing else
COPY --from=builder /workspace/order-service/target/order-service-*.jar app.jar

# Expose the port order-service listens on
EXPOSE 8081

# JVM flags:
#   -XX:+UseContainerSupport        — respect container CPU/memory limits (not host)
#   -XX:MaxRAMPercentage=75.0       — use up to 75% of container memory for heap
#   -Djava.security.egd=...         — faster startup (avoids blocking /dev/random)
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
