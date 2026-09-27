FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY .mvn .mvn
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S jobplatform && adduser -S jobplatform -G jobplatform
RUN mkdir -p /var/lib/job-platform/artifacts /var/lib/job-platform/file-assets && chown -R jobplatform:jobplatform /var/lib/job-platform
WORKDIR /app
COPY --from=build /workspace/target/job-platform-api-*.jar app.jar
USER jobplatform
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
