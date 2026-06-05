FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /workspace
COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY settings.gradle build.gradle ./
COPY notification/build.gradle notification/build.gradle
RUN sed -i 's/\r//' gradlew && chmod +x gradlew
RUN ./gradlew :notification:dependencies --no-daemon 2>/dev/null; true
COPY notification/src notification/src
RUN ./gradlew :notification:bootJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/notification/build/libs/*.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]