FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --gid 10001 badfisher \
    && useradd --uid 10001 --gid badfisher --no-create-home badfisher \
    && mkdir -p /app/runtime-data /app/config \
    && chown -R badfisher:badfisher /app
COPY --chown=badfisher:badfisher ai-log-bootstrap/target/ai-log-bootstrap-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
