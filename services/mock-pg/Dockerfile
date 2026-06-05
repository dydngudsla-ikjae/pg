FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /workspace
COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY settings.gradle build.gradle ./
COPY mock-pg/build.gradle mock-pg/build.gradle
RUN sed -i 's/\r//' gradlew && chmod +x gradlew
RUN ./gradlew :mock-pg:dependencies --no-daemon 2>/dev/null; true
COPY mock-pg/src mock-pg/src
RUN ./gradlew :mock-pg:bootJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/mock-pg/build/libs/*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]