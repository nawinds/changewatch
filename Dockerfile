FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew && ./gradlew :server:installDist --no-daemon

FROM eclipse-temurin:25-jre
RUN groupadd --system changewatch && useradd --system --gid changewatch changewatch
WORKDIR /app
COPY --from=build --chown=changewatch:changewatch /workspace/server/build/install/server/ ./
USER changewatch
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
