FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /workspace
COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY settings.gradle build.gradle ./
COPY api-gateway/build.gradle api-gateway/build.gradle
RUN sed -i 's/\r//' gradlew && chmod +x gradlew
RUN ./gradlew :api-gateway:dependencies --no-daemon 2>/dev/null; true
COPY api-gateway/src api-gateway/src
RUN ./gradlew :api-gateway:bootJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/api-gateway/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]