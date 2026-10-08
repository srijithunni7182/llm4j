# GetViral production image. Build from the REPOSITORY ROOT (it compiles the llm4j stack from source):
#   DOCKER_BUILDKIT=1 docker build -f examples/getviral/Dockerfile -t getviral .

# Any JRE 21 image with apt works as the runtime base (override with --build-arg RUNTIME_IMAGE=...).
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre

# ── build: the llm4j modules GetViral needs, then GetViral itself ─────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# The whole reactor (a few MB — see Dockerfile.dockerignore); Maven builds only what GetViral needs.
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -DskipTests -Dgpg.skip -Dmaven.javadoc.skip=true package -pl examples/getviral -am

# ── runtime: a slim JRE + fonts for the Reel renderer, running as non-root ────
FROM ${RUNTIME_IMAGE} AS runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home /app getviral
WORKDIR /app
COPY --from=build /src/examples/getviral/target/getviral-5.0.jar /app/getviral.jar
# Local disk is only scratch space on Cloud Run: media goes to Cloud Storage, everything else to Postgres.
ENV GETVIRAL_DATA_DIR=/tmp/getviral-data \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true" \
    PORT=8080
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/getviral.jar"]
