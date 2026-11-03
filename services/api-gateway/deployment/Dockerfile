FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY . .
# A Windows checkout may give gradlew CRLF line endings, which /bin/sh cannot run.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew :app-service:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=build /workspace/applications/app-service/build/libs/api-gateway.jar app.jar
USER appuser
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
