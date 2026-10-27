package io.github.llm4j.loom.deploy;

import java.util.stream.Collectors;

/** The container recipe: a runtime image, a non-root user, the project, an entrypoint. */
final class Docker {

    private Docker() {}

    static boolean daemon(DeployPlan p, DeployOptions o) {
        return o.schedule() && !p.schedules().isEmpty();
    }

    static String dockerfile(DeployPlan p, DeployOptions o) {
        String base = o.baseImage() == null ? DeployOptions.DEFAULT_BASE : o.baseImage();
        StringBuilder b = new StringBuilder();
        b.append("# Build from the project's folder:  docker build -f deploy/Dockerfile -t ")
                .append(Deploy.safeName(o.name()))
                .append(" .\n");
        b.append(
                "# Put the weave CLI jar (the same version you ran weave deploy with) at deploy/weave.jar first.\n");
        if (base.contains("@sha256:")) b.append("# The base image is pinned by digest.\n");
        else
            b.append(
                    "# The base image is a tag, not a digest: pin it (tag@sha256:...) before production.\n");
        b.append("FROM ").append(base).append('\n');
        b.append("RUN groupadd --system --gid ").append(Deploy.APP_UID).append(" loom \\\n");
        b.append("    && useradd --system --uid ")
                .append(Deploy.APP_UID)
                .append(" --gid loom --no-create-home --shell /usr/sbin/nologin loom \\\n");
        b.append("    && mkdir -p /data /app && chown loom:loom /data\n");
        b.append("WORKDIR /app\n");
        b.append("COPY deploy/weave.jar /app/weave.jar\n");
        b.append("COPY . /app/project\n");
        b.append("COPY deploy/entrypoint.sh /app/entrypoint.sh\n");
        b.append("RUN chmod 0555 /app/entrypoint.sh\n");
        b.append("ENV LOOM_STORE=/data\n");
        if (!o.journal().equals("none")) b.append("VOLUME /data\n");
        b.append("USER ").append(Deploy.APP_UID).append(':').append(Deploy.APP_UID).append('\n');
        b.append("# The script must still check; a stale or broken image is reported unhealthy.\n");
        b.append("HEALTHCHECK --interval=5m --timeout=60s --start-period=60s --retries=2 \\\n");
        b.append("    CMD java -jar /app/weave.jar check /app/project/")
                .append(p.script())
                .append(" --no-env > /dev/null 2>&1 || exit 1\n");
        if (o.expose() != null && o.expose().startsWith("http:"))
            b.append("EXPOSE ").append(o.expose().substring(5)).append('\n');
        b.append("ENTRYPOINT [\"/app/entrypoint.sh\"]\n");
        return b.toString();
    }

    static String dockerignore() {
        return ".git\n.env\n.env.*\n!.env.example\n"
                + "# the build needs only these two files from deploy/\n"
                + "deploy/*\n!deploy/weave.jar\n!deploy/entrypoint.sh\n"
                + "**/target/\n**/node_modules/\nloom-inspect-outbox/\n*.log\n.loom-triggers/\n";
    }

    static String entrypoint(DeployPlan p, DeployOptions o) {
        StringBuilder b = new StringBuilder();
        b.append("#!/bin/sh\nset -eu\n");
        b.append(
                "# Secrets arrive as environment variables, or as files under /run/secrets; nothing is baked into the image.\n");
        b.append(
                "for f in /run/secrets/*; do\n"
                        + "  [ -f \"$f\" ] || continue\n"
                        + "  name=$(basename \"$f\" | tr 'a-z-' 'A-Z_')\n"
                        + "  case \"$name\" in\n"
                        + "    ''|[0-9]*|*[!A-Z0-9_]*) echo \"weave: skipping the secret file $f: its name is not a variable name\" >&2; continue;;\n"
                        + "  esac\n"
                        + "  export \"$name=$(cat \"$f\")\"\n"
                        + "done\n");
        String observe = "";
        if (o.withFabric())
            observe =
                    " ${LOOM_FABRIC_URL:+--observe-fabric \"$LOOM_FABRIC_URL\" --observe-name "
                            + Deploy.safeName(o.name())
                            + "}";
        if (daemon(p, o)) {
            b.append("mkdir -p /data/triggers\n");
            b.append("exec java -jar /app/weave.jar daemon /data/triggers")
                    .append(observe)
                    .append('\n');
        } else {
            b.append("exec java -jar /app/weave.jar run /app/project/")
                    .append(p.script())
                    .append(observe)
                    .append(" \"$@\"\n");
        }
        return b.toString();
    }

    static String envExample(DeployPlan p, DeployOptions o) {
        StringBuilder b = new StringBuilder();
        b.append("# Names only. Copy to .env and fill the values in; never commit a real one.\n");
        for (String n : p.envNames()) b.append(n).append("=<your value>\n");
        if (o.withFabric()) {
            b.append("LOOM_FABRIC_URL=http://fabric:9090\n");
            b.append("LOOM_FABRIC_CLIENT_KEY=<this process's key from fabric client add>\n");
        }
        if (p.localModels()) b.append("OLLAMA_HOST=<where the local model server is>\n");
        return b.toString();
    }

    static String names(java.util.List<String> l) {
        return l.stream().collect(Collectors.joining(", "));
    }
}
