# Build from project root: docker build -f payment-service/Dockerfile .
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /workspace

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

RUN mvn dependency:go-offline -pl payment-service -am -B -q 2>/dev/null || true

COPY shared-lib/src ./shared-lib/src
COPY payment-service/src ./payment-service/src

RUN mvn package -pl payment-service -am -DskipTests -B -q

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app

COPY --from=builder /workspace/payment-service/target/payment-service-*.jar app.jar

EXPOSE 8083

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
