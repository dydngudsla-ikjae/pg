FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /workspace
COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY settings.gradle build.gradle ./
COPY virtual-account/build.gradle virtual-account/build.gradle
RUN sed -i 's/\r//' gradlew && chmod +x gradlew
RUN ./gradlew :virtual-account:dependencies --no-daemon 2>/dev/null; true
COPY virtual-account/src virtual-account/src
RUN ./gradlew :virtual-account:bootJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/virtual-account/build/libs/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]