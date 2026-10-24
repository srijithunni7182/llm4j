package io.github.llm4j.loom.deploy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a deployment as text: a Dockerfile and its entrypoint, a compose file, or Kubernetes
 * manifests. A pure function of the plan and the options: no clock, no network, no Docker, and the
 * same input gives byte-identical files. Secrets are never written, only the names they come from.
 */
public final class Deploy {

    private Deploy() {}

    public static final String VERSION = "1";
    public static final int APP_UID = 10001;
    static final int FABRIC_PORT = 9090;

    /** Why a deployment cannot be made as asked (a bind that exposes Fabric without protection). */
    public static final class Refused extends IllegalArgumentException {
        public Refused(String message) {
            super(message);
        }
    }

    public static Map<String, String> render(DeployPlan plan, DeployOptions o, String scriptHash) {
        checkBind(o);
        Map<String, String> files = new LinkedHashMap<>();
        String from = plan.script() + " sha256:" + scriptHash;
        files.put("Dockerfile", stamp("#", from, Docker.dockerfile(plan, o)));
        files.put(".dockerignore", stamp("#", from, Docker.dockerignore()));
        files.put("entrypoint.sh", stamp("#", from, Docker.entrypoint(plan, o)));
        files.put(".env.example", stamp("#", from, Docker.envExample(plan, o)));
        switch (o.target()) {
            case "docker" -> {}
            case "compose" -> files.put("compose.yaml", stamp("#", from, Compose.render(plan, o)));
            case "k8s" -> Kube.render(plan, o).forEach((k, v) -> files.put(k, stamp("#", from, v)));
            default -> throw new IllegalArgumentException("--target is docker, compose or k8s.");
        }
        files.put("README.md", stamp("<!--", from, Readme.render(plan, o, files.keySet())));
        return files;
    }

    /** Fabric's page is published on a loopback address only; anything wider needs TLS in front. */
    static void checkBind(DeployOptions o) {
        String addr = o.fabricPublish() == null ? "127.0.0.1" : o.fabricPublish();
        if (!(addr.equals("127.0.0.1") || addr.equals("localhost") || addr.equals("::1")))
            throw new Refused(
                    "Fabric will not be published on "
                            + addr
                            + ": its page needs access control and TLS first. Publish it on 127.0.0.1 and put a reverse proxy that terminates TLS in front (see the deployer guide), or run it with TLS yourself.");
        if (o.expose() != null
                && !o.expose().equals("none")
                && !o.expose().matches("http:\\d{2,5}"))
            throw new IllegalArgumentException("--expose is none or http:PORT.");
    }

    // ---- the header: where a file came from, and a hash of its body to tell it was edited

    static final String MARK = "weave deploy " + VERSION;

    static String stamp(String comment, String from, String body) {
        String hash = hash(body);
        String line = MARK + " from " + from + " body " + hash;
        String header =
                comment.equals("<!--") ? "<!-- " + line + " -->\n" : comment + " " + line + "\n";
        if (body.startsWith("#!")) {
            int nl = body.indexOf('\n') + 1;
            return body.substring(0, nl) + header + body.substring(nl);
        }
        return header + body;
    }

    static String hash(String body) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(body.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The body a stamped file carries (without the stamp line). */
    public static String bodyOf(String stamped) {
        List<String> lines = new ArrayList<>(List.of(stamped.split("\n", -1)));
        for (int i = 0; i < Math.min(3, lines.size()); i++)
            if (lines.get(i).contains(MARK + " from ")) {
                lines.remove(i);
                break;
            }
        return String.join("\n", lines);
    }

    /** The hash a stamped file says its body had when written. */
    public static String recordedHash(String stamped) {
        for (String l : stamped.split("\n", 4)) {
            int i = l.indexOf(" body ");
            if (l.contains(MARK + " from ") && i > 0)
                return l.substring(i + 6).replace("-->", "").trim();
        }
        return null;
    }

    /** True when the file's body is no longer what was generated (somebody edited it). */
    public static boolean edited(String stamped) {
        String recorded = recordedHash(stamped);
        return recorded != null && !recorded.equals(hash(bodyOf(stamped)));
    }

    /** Names are safe in a file name, an image tag and a Kubernetes label. */
    public static String safeName(String s) {
        String n =
                s.toLowerCase(java.util.Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-|-$", "");
        return n.isEmpty() ? "loom-app" : n.length() > 40 ? n.substring(0, 40) : n;
    }
}
