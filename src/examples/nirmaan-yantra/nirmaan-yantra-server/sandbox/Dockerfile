# The image behind NIRMAAN_SANDBOX=docker (or podman): every build, test and end to end command the factory runs happens in a
# throwaway container made from this. It holds the toolchains the factory's specs usually ask for, and nothing of yours.
#
#   docker build -t nirmaan-sandbox:latest sandbox/
#   NIRMAAN_SANDBOX=docker NIRMAAN_REQUIRE_CONTAINER=true ./your-server
#
# NOT YET BUILT OR RUN by the project's tests: build it once and run `mvn test`-style smoke checks of your own before relying on it.
# Add the toolchains your ideas need (Rust, .NET, ...) here; a spec that asks for a tool the image lacks fails its build cleanly.
FROM eclipse-temurin:17-jdk-jammy

RUN apt-get update \
 && apt-get install -y --no-install-recommends maven nodejs npm python3 python3-pip python3-venv golang-go git ca-certificates bash \
 && rm -rf /var/lib/apt/lists/* \
 && pip3 install --no-cache-dir pytest

# The factory runs the container as your own uid:gid with --cap-drop ALL, so nothing here needs root at run time.
# HOME is set by the factory to a mounted tool-home folder (caches survive between commands); /work is the project.
WORKDIR /work
