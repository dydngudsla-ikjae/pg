FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /workspace
COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY settings.gradle build.gradle ./
COPY billing/build.gradle billing/build.gradle
RUN sed -i 's/\r//' gradlew && chmod +x gradlew
RUN ./gradlew :billing:dependencies --no-daemon 2>/dev/null; true
COPY billing/src billing/src
RUN ./gradlew :billing:bootJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/billing/build/libs/*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]