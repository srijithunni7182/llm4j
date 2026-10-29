package io.github.llm4j.loom.doctor;

import io.github.llm4j.loom.deploy.DeployPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * What is wrong with the setup, and the one thing to do about it. Every check is local unless the
 * caller asks for the network, never prints a secret (not a value, a prefix or a length: only the
 * names of the variables a script needs), and ends in a single action.
 */
public final class Doctor {

    private Doctor() {}

    public enum Status {
        OK,
        WARN,
        FAIL,
        SKIPPED
    }

    /**
     * @param action the one command or setting that fixes it; empty when ok or skipped
     */
    public record Check(String name, Status status, String reason, String action) {}

    /** A TLS connect to a host, with no request and no credentials. */
    public interface Probe {
        /** Null when it connected; otherwise a short reason. */
        String connect(String host, int port, Duration timeout);
    }

    /**
     * @param script the script, or null when there is none
     * @param plan what the script needs, or null
     * @param checkProblem the first problem {@code weave check} found, null when it checks clean,
     *     or null with {@code checked=false}
     */
    public record Context(
            Path script,
            DeployPlan plan,
            boolean checked,
            String checkProblem,
            List<String> unknownModels,
            Function<String, String> env,
            Path cwd,
            int javaFeature,
            String javaHome,
            String javaHomeEnv,
            String version,
            Instant now,
            boolean network,
            Probe probe,
            List<String> envExampleWithValues) {}

    public static List<Check> run(Context c) {
        List<Check> out = new ArrayList<>();
        out.add(java(c));
        out.add(cli(c));
        out.add(project(c));
        out.addAll(keys(c));
        out.add(models(c));
        out.add(network(c));
        out.add(store(c));
        out.add(prices(c));
        out.add(fabric(c));
        return out;
    }

    // ---- the checks

    public static Check java(Context c) {
        if (c.javaFeature() < 17)
            return new Check(
                    "Java",
                    Status.FAIL,
                    "Java " + c.javaFeature() + " is too old; Loom needs 17 or newer.",
                    "Install a JDK 17+ (https://adoptium.net) and put it first on PATH.");
        if (c.javaHomeEnv() != null
                && !c.javaHomeEnv().isBlank()
                && !samePath(c.javaHomeEnv(), c.javaHome()))
            return new Check(
                    "Java",
                    Status.WARN,
                    "JAVA_HOME points to a different Java than the one running (Java "
                            + c.javaFeature()
                            + ").",
                    "Make them agree: set JAVA_HOME to " + c.javaHome() + ", or unset it.");
        return new Check("Java", Status.OK, "Java " + c.javaFeature() + ".", "");
    }

    private static boolean samePath(String a, String b) {
        try {
            return Path.of(a).toRealPath().equals(Path.of(b).toRealPath());
        } catch (IOException | RuntimeException e) {
            return a.equals(b);
        }
    }

    /**
     * Two Loom jars that share a version number but are different builds are named in one sentence,
     * with the fix; nothing is returned when all the jars found agree (or only one was found).
     */
    public static java.util.Optional<Check> jars(List<JarDiscovery.Found> found) {
        for (int i = 0; i < found.size(); i++) {
            for (int j = i + 1; j < found.size(); j++) {
                var a = found.get(i);
                var b = found.get(j);
                if (!a.identity().version().equals(b.identity().version())
                        || a.identity().sameBuildAs(b.identity())) continue;
                var older = isOlder(a, b) ? a : b;
                var newer = older == a ? b : a;
                return java.util.Optional.of(
                        new Check(
                                "Jars",
                                Status.WARN,
                                older.label()
                                        + " ("
                                        + older.identity().describe()
                                        + ") and "
                                        + newer.label()
                                        + " ("
                                        + newer.identity().describe()
                                        + ") both say "
                                        + a.identity().version()
                                        + ", but they are different builds.",
                                "Remove the older copy"
                                        + (older.path() == null ? "" : " (" + older.path() + ")")
                                        + ", or set WEAVE_JAR to the one you mean."));
            }
        }
        return java.util.Optional.empty();
    }

    private static boolean isOlder(JarDiscovery.Found a, JarDiscovery.Found b) {
        String ta = a.identity().builtAt();
        String tb = b.identity().builtAt();
        if (ta == null) return true;
        if (tb == null) return false;
        return ta.compareTo(tb) <= 0;
    }

    public static Check cli(Context c) {
        return new Check("CLI", Status.OK, "weave " + c.version() + ".", "");
    }

    public static Check project(Context c) {
        if (c.script() == null)
            return new Check(
                    "Project",
                    Status.SKIPPED,
                    "No .loom script here.",
                    "Run `weave try` for a demo, or `weave init` to start a project.");
        if (!c.checked())
            return new Check("Project", Status.SKIPPED, "The script was not checked.", "");
        if (c.checkProblem() != null)
            return new Check(
                    "Project",
                    Status.FAIL,
                    c.script().getFileName() + " does not check: " + clip(c.checkProblem()),
                    "Run `weave check " + c.script().getFileName() + "` for the details.");
        return new Check("Project", Status.OK, c.script().getFileName() + " checks clean.", "");
    }

    static List<Check> keys(Context c) {
        List<Check> out = new ArrayList<>();
        if (c.plan() == null) {
            out.add(new Check("Keys", Status.SKIPPED, "No script, so no keys to look for.", ""));
            return out;
        }
        List<String> missing = new ArrayList<>();
        for (String n : c.plan().envNames()) {
            String v = c.env().apply(n);
            if (v == null || v.isBlank()) missing.add(n);
        }
        if (c.plan().envNames().isEmpty())
            out.add(new Check("Keys", Status.OK, "The script needs no key or variable.", ""));
        else if (missing.isEmpty())
            out.add(
                    new Check(
                            "Keys",
                            Status.OK,
                            "All "
                                    + c.plan().envNames().size()
                                    + " variable(s) the script needs are set: "
                                    + String.join(", ", c.plan().envNames())
                                    + ".",
                            ""));
        else
            out.add(
                    new Check(
                            "Keys",
                            Status.FAIL,
                            "Not set: " + String.join(", ", missing) + ".",
                            "Export "
                                    + missing.get(0)
                                    + " or put it in .env (`weave secret` keeps it in a store)."));
        if (!c.envExampleWithValues().isEmpty())
            out.add(
                    new Check(
                            "Keys",
                            Status.WARN,
                            ".env.example holds what looks like a real value for "
                                    + String.join(", ", c.envExampleWithValues())
                                    + ".",
                            "Replace it with a placeholder in .env.example, and rotate the key if it was committed."));
        return out;
    }

    private static final List<String> KNOWN =
            List.of(
                    "gemini-2.5-flash",
                    "gemini-2.5-pro",
                    "claude-haiku-4-5",
                    "claude-sonnet-4-5",
                    "gpt-4.1-mini",
                    "gpt-4.1",
                    "ollama/llama3");

    public static Check models(Context c) {
        if (c.plan() == null) return new Check("Models", Status.SKIPPED, "No script.", "");
        if (c.unknownModels().isEmpty())
            return new Check(
                    "Models", Status.OK, "Every model name in the script is one Loom knows.", "");
        String m = c.unknownModels().get(0);
        String near = nearest(m);
        return new Check(
                "Models",
                Status.FAIL,
                "Loom does not know the model \"" + m + "\".",
                near != null
                        ? "Did you mean `" + near + "`? Fix the name in the script."
                        : "Use a known model name, or declare a provider in the script.");
    }

    static String nearest(String m) {
        String best = null;
        int bd = Integer.MAX_VALUE;
        for (String k : KNOWN) {
            int d = distance(m.toLowerCase(Locale.ROOT), k);
            if (d < bd) {
                bd = d;
                best = k;
            }
        }
        return bd <= Math.max(4, m.length() / 3) ? best : null;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] =
                        Math.min(
                                Math.min(cur[j - 1] + 1, prev[j] + 1),
                                prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            prev = cur;
        }
        return prev[b.length()];
    }

    public static Check network(Context c) {
        if (!c.network())
            return new Check(
                    "Network",
                    Status.SKIPPED,
                    "Not checked (it makes connections).",
                    "Run `weave doctor --network` to test the connection to each provider.");
        if (c.plan() == null || c.plan().egressHosts().isEmpty())
            return new Check(
                    "Network", Status.SKIPPED, "The script talks to no hosted provider.", "");
        List<String> failed = new ArrayList<>();
        for (String h : c.plan().egressHosts()) {
            String problem = c.probe().connect(h, 443, Duration.ofSeconds(5));
            if (problem != null) failed.add(h + " (" + problem + ")");
        }
        boolean proxy = hasProxy(c);
        if (failed.isEmpty())
            return new Check(
                    "Network",
                    Status.OK,
                    "Connected to "
                            + String.join(", ", c.plan().egressHosts())
                            + (proxy ? " (a proxy is configured)" : "")
                            + ".",
                    "");
        return new Check(
                "Network",
                Status.FAIL,
                "Could not connect to " + String.join("; ", failed) + ".",
                proxy
                        ? "Check the proxy in HTTPS_PROXY allows these hosts, and that its CA is in the Java trust store."
                        : "If you are behind a proxy, set HTTPS_PROXY; otherwise check the firewall allows port 443 to these hosts.");
    }

    private static boolean hasProxy(Context c) {
        for (String n : List.of("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")) {
            String v = c.env().apply(n);
            if (v != null && !v.isBlank()) return true;
        }
        return false;
    }

    public static Check store(Context c) {
        Path dir = c.cwd().resolve(".loom-triggers");
        Path probe = Files.isDirectory(dir) ? dir : c.cwd();
        if (!Files.isWritable(probe))
            return new Check(
                    "Store",
                    Status.FAIL,
                    "Runs cannot be written under " + probe + ".",
                    "Run from a folder you can write to, or pass `--store <dir>`.");
        long free = probe.toFile().getUsableSpace();
        if (free < 50L * 1024 * 1024)
            return new Check(
                    "Store",
                    Status.WARN,
                    "Less than 50 MB is free where runs are kept.",
                    "Free some space, or pass `--store <dir>` on a bigger disk.");
        return new Check("Store", Status.OK, "Runs can be written under " + probe + ".", "");
    }

    public static Check prices(Context c) {
        Path file =
                c.script() != null
                        ? c.script().toAbsolutePath().getParent().resolve("prices.properties")
                        : c.cwd().resolve("prices.properties");
        if (!Files.isRegularFile(file))
            return new Check(
                    "Prices",
                    Status.SKIPPED,
                    "No prices.properties; costs use the built-in prices and are estimates.",
                    "Add a `prices.properties` (see `weave guide`) for costs you can rely on.");
        try {
            Instant m = Files.getLastModifiedTime(file).toInstant();
            if (Duration.between(m, c.now()).toDays() > 90)
                return new Check(
                        "Prices",
                        Status.WARN,
                        "prices.properties was last changed more than 90 days ago; costs are estimates until it is current.",
                        "Update `prices.properties` from your providers' price pages.");
        } catch (IOException e) {
            return new Check("Prices", Status.SKIPPED, "prices.properties could not be read.", "");
        }
        return new Check("Prices", Status.OK, "prices.properties is recent.", "");
    }

    public static Check fabric(Context c) {
        String url = c.env().apply("LOOM_FABRIC_URL");
        if (url == null || url.isBlank())
            return new Check("Fabric", Status.SKIPPED, "No Fabric server configured.", "");
        try {
            java.net.URI u = java.net.URI.create(url.trim());
            boolean loopback =
                    "localhost".equals(u.getHost())
                            || "127.0.0.1".equals(u.getHost())
                            || "::1".equals(u.getHost())
                            || "[::1]".equals(u.getHost());
            if ("http".equals(u.getScheme()) && !loopback)
                return new Check(
                        "Fabric",
                        Status.WARN,
                        "LOOM_FABRIC_URL uses plain http to a host that is not this machine.",
                        "Use https (`fabric tls init`), or a TLS reverse proxy in front of Fabric.");
            if (u.getScheme() == null || u.getHost() == null)
                return new Check(
                        "Fabric",
                        Status.FAIL,
                        "LOOM_FABRIC_URL is not a web address.",
                        "Set it like `http://127.0.0.1:9090`.");
        } catch (IllegalArgumentException e) {
            return new Check(
                    "Fabric",
                    Status.FAIL,
                    "LOOM_FABRIC_URL is not a web address.",
                    "Set it like `http://127.0.0.1:9090`.");
        }
        return new Check("Fabric", Status.OK, "LOOM_FABRIC_URL is set.", "");
    }

    // ---- the verdict

    public static int exitCode(List<Check> checks) {
        if (checks.stream().anyMatch(k -> k.status() == Status.FAIL)) return 2;
        if (checks.stream().anyMatch(k -> k.status() == Status.WARN)) return 1;
        return 0;
    }

    public static String verdict(List<Check> checks) {
        long ok = checks.stream().filter(k -> k.status() == Status.OK).count();
        long warn = checks.stream().filter(k -> k.status() == Status.WARN).count();
        long fail = checks.stream().filter(k -> k.status() == Status.FAIL).count();
        if (fail > 0)
            return "Not ready: "
                    + fail
                    + " failure"
                    + (fail == 1 ? "" : "s")
                    + " — fix the first one first ("
                    + ok
                    + " ok, "
                    + warn
                    + " warning"
                    + (warn == 1 ? "" : "s")
                    + ").";
        return "Ready: " + ok + " ok, " + warn + " warning" + (warn == 1 ? "" : "s") + ".";
    }

    private static String clip(String s) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 110 ? one.substring(0, 110) + "…" : one;
    }
}
