FROM maven:3-eclipse-temurin-26 AS build

WORKDIR /workspace
COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress -DskipTests dependency:go-offline

COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests clean package

FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S cuadra && adduser -S cuadra -G cuadra
WORKDIR /app
COPY --from=build --chown=cuadra:cuadra /workspace/target/cuadra-api-*.jar app.jar

USER cuadra
ENV PORT=8080
EXPOSE 8080

# La zona horaria del servidor es UTC: cada negocio tiene la suya y se calcula con java.time.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
