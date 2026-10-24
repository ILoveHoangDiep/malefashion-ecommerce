# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q -DskipTests dependency:go-offline

COPY src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

COPY --from=build /workspace/target/inventory-service-*.jar app.jar

USER app
EXPOSE 8085

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
