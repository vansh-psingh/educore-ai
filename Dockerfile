# EDUCORE AI - Cloud Run deployment image
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/educore-ai-1.0.0.jar app.jar
# Cloud Run injects $PORT; the app also reads it (falls back to 8080)
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "web"]
