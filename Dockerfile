FROM gradle:8.10-jdk21-alpine AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S shresta && adduser -S shresta -G shresta
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/shresta-be.jar
RUN chown -R shresta:shresta /app
USER shresta
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 CMD wget -qO- http://localhost:8090/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+UseContainerSupport", "-jar", "/app/shresta-be.jar"]
