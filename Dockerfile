# The jar is arch-independent, so build it natively and only run the small runtime stage per platform.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build

# Optional corporate CA certificates; empty on a normal network.
COPY certs/ /tmp/certs/
COPY docker/install-certs.sh /tmp/install-certs.sh
RUN chmod +x /tmp/install-certs.sh && /tmp/install-certs.sh /tmp/certs

WORKDIR /src

# Resolve the Gradle distribution and plugins in their own layer so source edits don't refetch them.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN chmod +x gradlew && ./gradlew --no-daemon --quiet help

COPY src src
RUN ./gradlew --no-daemon installDist -x test


FROM eclipse-temurin:21-jre

# python3/curl/unzip let the container build its own static GTFS bundle on first start.
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 curl unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# The running server calls the NTA API over HTTPS, so it needs the same trust anchors.
COPY certs/ /tmp/certs/
COPY docker/install-certs.sh /tmp/install-certs.sh
RUN chmod +x /tmp/install-certs.sh && /tmp/install-certs.sh /tmp/certs && rm -rf /tmp/certs

WORKDIR /app

COPY --from=build /src/build/install/dublin-bus-server ./
COPY scripts/fetch-gtfs-data.sh scripts/filter_gtfs.py ./scripts/
COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh

RUN chmod +x /usr/local/bin/entrypoint.sh ./scripts/fetch-gtfs-data.sh ./bin/dublin-bus-server \
    && useradd --system --create-home --uid 10001 appuser \
    && mkdir -p /data \
    && chown -R appuser:appuser /data /app

USER appuser

ENV GTFS_STATIC_PATH=/data/gtfs_static.json \
    PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

VOLUME ["/data"]
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=300s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${PORT}/health" || exit 1

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
