package io.github.llm4j.loom.deploy;

/** One compose file: the app, optionally Fabric, a volume, an internal network. */
final class Compose {

    private Compose() {}

    static String render(DeployPlan p, DeployOptions o) {
        String name = Deploy.safeName(o.name());
        StringBuilder b = new StringBuilder();
        b.append("name: ").append(name).append('\n');
        b.append("services:\n");
        b.append("  app:\n");
        b.append("    build:\n      context: ..\n      dockerfile: deploy/Dockerfile\n");
        b.append("    image: ").append(name).append("\n");
        b.append("    user: \"")
                .append(Deploy.APP_UID)
                .append(':')
                .append(Deploy.APP_UID)
                .append("\"\n");
        b.append("    read_only: true\n");
        b.append("    tmpfs:\n      - /tmp\n");
        // no volume (none, or a database): the root is read-only, so /data is scratch memory
        if (!o.journal().equals("volume"))
            b.append("      - /data:uid=")
                    .append(Deploy.APP_UID)
                    .append(",gid=")
                    .append(Deploy.APP_UID)
                    .append('\n');
        b.append("    cap_drop:\n      - ALL\n");
        b.append("    security_opt:\n      - no-new-privileges:true\n");
        b.append("    restart: unless-stopped\n");
        b.append("    mem_limit: 1g\n    pids_limit: 256\n");
        b.append("    environment:\n");
        for (String n : p.envNames()) b.append("      - ").append(n).append('\n');
        if (o.withFabric())
            b.append("      - LOOM_FABRIC_URL=http://fabric:")
                    .append(Deploy.FABRIC_PORT)
                    .append('\n');
        if (!o.journal().equals("none") && !o.journal().equals("volume"))
            b.append("      - LOOM_JOURNAL_URL=").append(o.journal()).append('\n');
        if (o.journal().equals("volume")) b.append("    volumes:\n      - data:/data\n");
        if (o.withFabric()) {
            b.append("    secrets:\n      - loom_fabric_client_key\n");
            b.append("    depends_on:\n      fabric:\n        condition: service_healthy\n");
            b.append("    networks:\n      - internal\n      - default\n");
        }
        if (o.expose() != null && o.expose().startsWith("http:")) {
            String port = o.expose().substring(5);
            b.append("    ports:\n      - \"127.0.0.1:")
                    .append(port)
                    .append(':')
                    .append(port)
                    .append("\"\n");
        }
        if (o.withFabric()) {
            String bind = o.fabricPublish() == null ? "127.0.0.1" : o.fabricPublish();
            b.append("  fabric:\n");
            b.append("    image: ")
                    .append(
                            o.fabricImage() == null
                                    ? DeployOptions.DEFAULT_FABRIC
                                    : o.fabricImage())
                    .append('\n');
            b.append("    user: \"")
                    .append(Deploy.APP_UID)
                    .append(':')
                    .append(Deploy.APP_UID)
                    .append("\"\n");
            b.append("    read_only: true\n    tmpfs:\n      - /tmp\n");
            b.append("    cap_drop:\n      - ALL\n");
            b.append("    security_opt:\n      - no-new-privileges:true\n");
            b.append("    restart: unless-stopped\n");
            b.append(
                    "    command: [\"serve\", \"--data\", \"/fabric\", \"--bind\", \"0.0.0.0\", \"--trust-proxy\"]\n");
            b.append(
                    "    # --trust-proxy: the page is published on loopback only; put a TLS proxy in front before any wider access.\n");
            b.append("    volumes:\n      - fabric:/fabric\n");
            b.append("    ports:\n      - \"")
                    .append(bind)
                    .append(':')
                    .append(Deploy.FABRIC_PORT)
                    .append(':')
                    .append(Deploy.FABRIC_PORT)
                    .append("\"\n");
            b.append("    healthcheck:\n      test: [\"CMD-SHELL\", \"wget -qO- http://127.0.0.1:")
                    .append(Deploy.FABRIC_PORT)
                    .append("/api/session > /dev/null || exit 1\"]\n");
            b.append("      interval: 15s\n      timeout: 5s\n      retries: 5\n");
            // a container on an internal-only network cannot publish a port, so Fabric also joins
            // the
            // default network; the app reaches it over "internal" either way
            b.append("    networks:\n      - internal\n      - default\n");
        }
        if (o.journal().equals("volume") || o.withFabric()) b.append("volumes:\n");
        if (o.journal().equals("volume")) b.append("  data: {}\n");
        if (o.withFabric()) b.append("  fabric: {}\n");
        if (o.withFabric()) {
            b.append("networks:\n  internal:\n    internal: true\n");
            b.append(
                    "secrets:\n  loom_fabric_client_key:\n    file: ./secrets/loom_fabric_client_key\n");
        }
        return b.toString();
    }
}
