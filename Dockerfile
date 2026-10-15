# syntax=docker/dockerfile:1.7
# One build stage compiles the whole multi-module project once; each service is a small runtime target:
#   docker build --target guard-proxy -t veil/guard-proxy .

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY contracts/pom.xml contracts/
COPY detection-core/pom.xml detection-core/
COPY clients/java/pom.xml clients/java/
COPY services/edge-gateway/pom.xml services/edge-gateway/
COPY services/control-plane/pom.xml services/control-plane/
COPY services/guard-proxy/pom.xml services/guard-proxy/
COPY services/insight-service/pom.xml services/insight-service/
COPY contracts/src contracts/src
COPY detection-core/src detection-core/src
COPY clients/java/src clients/java/src
COPY services services
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app
WORKDIR /app
USER 10001
# Size the heap from the container's memory limit, and fail fast instead of limping on after OOM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime AS edge-gateway
COPY --from=build /src/services/edge-gateway/target/edge-gateway-1.0.0.jar /app/app.jar
EXPOSE 8080

FROM runtime AS control-plane
COPY --from=build /src/services/control-plane/target/control-plane-1.0.0.jar /app/app.jar
EXPOSE 8202

FROM runtime AS guard-proxy
COPY --from=build /src/services/guard-proxy/target/guard-proxy-1.0.0.jar /app/app.jar
EXPOSE 8201

FROM runtime AS insight-service
COPY --from=build /src/services/insight-service/target/insight-service-1.0.0.jar /app/app.jar
EXPOSE 8203
