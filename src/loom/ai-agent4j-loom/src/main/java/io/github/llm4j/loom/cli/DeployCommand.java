package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.deploy.Deploy;
import io.github.llm4j.loom.deploy.DeployOptions;
import io.github.llm4j.loom.deploy.DeployPlan;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.travel.ScriptHash;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave deploy}: the files to run a workflow in a container, from the script. It writes text
 * (a Dockerfile, a compose file or Kubernetes manifests, an environment template, a README) and
 * does not build, push or apply anything. The review's findings are shown as a warning and never
 * stop it (only {@code --require-clean} does). Secret values are never written, only their names.
 */
@Command(
        name = "deploy",
        description =
                "Writes a container recipe, a compose file or Kubernetes manifests for a workflow, from the script: env names, hosts, volumes and schedules are read from it. Writes text only; secrets never appear. --verify checks a directory against the script.")
final class DeployCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "script.loom", description = "The script.")
    File script;

    @Option(names = "--target", defaultValue = "compose", description = "docker, compose or k8s.")
    String target;

    @Option(names = "--out", description = "Where to write (default ./deploy).")
    File out = new File("deploy");

    @Option(names = "--name", description = "The app and image name (default: the script's name).")
    String name;

    @Option(
            names = "--base-image",
            description = "The runtime image; give tag@sha256:... to pin it.")
    String baseImage;

    @Option(
            names = "--with-fabric",
            negatable = true,
            defaultValue = "true",
            fallbackValue = "true",
            description =
                    "Include a Fabric server and report to it (default yes for compose and k8s).")
    boolean withFabric;

    @Option(names = "--fabric-image", description = "The Fabric server image.")
    String fabricImage;

    @Option(names = "--journal", defaultValue = "volume", description = "volume, jdbc:URL or none.")
    String journal;

    @Option(
            names = "--expose",
            defaultValue = "none",
            description = "none, or http:PORT for an app that listens.")
    String expose;

    @Option(
            names = "--schedule",
            defaultValue = "keep",
            description = "keep (run the daemon for the script's schedules) or none.")
    String schedule;

    @Option(
            names = "--fabric-publish",
            defaultValue = "127.0.0.1",
            description = "The address Fabric's page is published on (loopback only).")
    String fabricPublish;

    @Option(
            names = "--require-clean",
            description = "Exit 1 when open high findings remain in the review.")
    boolean requireClean;

    @Option(names = "--dry-run", description = "Print the files, write nothing.")
    boolean dryRun;

    @Option(names = "--force", description = "Replace files that were edited by hand.")
    boolean force;

    @Option(
            names = "--verify",
            paramLabel = "dir",
            description = "Check a directory against the script; exit 1 on drift.")
    File verify;

    @Override
    public Integer call() {
        return run(this, WeaveEnv.system());
    }

    static int run(DeployCommand c, WeaveEnv env) {
        try {
            if (!c.script.isFile()) {
                env.err().println("Error: the script " + c.script + " is not there.");
                return 2;
            }
            if (!List.of("docker", "compose", "k8s").contains(c.target)) {
                env.err().println("Error: --target is docker, compose or k8s.");
                return 2;
            }
            if (!(c.journal.equals("volume")
                    || c.journal.equals("none")
                    || c.journal.startsWith("jdbc:"))) {
                env.err().println("Error: --journal is volume, none or jdbc:URL.");
                return 2;
            }
            int code =
                    WeaveCLI.check(
                            c.script,
                            null,
                            new WeaveCLI.CheckSettings(false, false, true, false),
                            env);
            if (code != 0) {
                env.err().println("Error: the script does not check, so nothing was generated.");
                return 1;
            }
            Review review = review(c.script, env);
            if (review.warning != null) env.err().println(review.warning);
            if (!review.ran())
                env.err()
                        .println(
                                "⚠ weave review could not run: "
                                        + review.summary
                                        + " Not blocking the files; --require-clean would stop here.");
            if (blocks(c.requireClean, review)) {
                env.err()
                        .println(
                                review.ran()
                                        ? "Error: --require-clean: "
                                                + review.high()
                                                + " high finding(s) are open; nothing was generated."
                                        : "Error: --require-clean: the review could not run, so the script is not known to be clean; nothing was generated. Run `weave review` to see why.");
                return 1;
            }
            var loaded = new LoomLoader().load(c.script.toString());
            DeployPlan plan = DeployPlan.derive(loaded, c.script.getName());
            String name = c.name != null ? c.name : Deploy.safeName(stem(c.script));
            boolean fabric = c.withFabric && !c.target.equals("docker");
            DeployOptions o =
                    new DeployOptions(
                            c.target,
                            name,
                            c.baseImage,
                            fabric,
                            c.fabricImage,
                            c.journal,
                            c.expose,
                            c.schedule.equals("keep"),
                            c.fabricPublish,
                            review.summary);
            Map<String, String> files =
                    Deploy.render(plan, o, ScriptHash.of(c.script.toPath()).substring(0, 16));
            Path out = c.out.toPath();
            if (c.verify != null) return verify(c.verify.toPath(), files, plan, env);
            if (c.dryRun) {
                files.forEach((k, v) -> env.out().println("=== " + k + " ===\n" + v));
                return 0;
            }
            List<String> wrote = new ArrayList<>();
            List<String> kept = new ArrayList<>();
            for (var f : files.entrySet()) {
                Path target = out.resolve(f.getKey());
                if (Files.isRegularFile(target)) {
                    String existing = Files.readString(target, StandardCharsets.UTF_8);
                    if (existing.equals(f.getValue())) continue;
                    if (Deploy.edited(existing) && !c.force) {
                        kept.add(
                                f.getKey()
                                        + " (differs at line "
                                        + firstDifference(existing, f.getValue())
                                        + ")");
                        continue;
                    }
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, f.getValue(), StandardCharsets.UTF_8);
                if (f.getKey().equals("entrypoint.sh")) target.toFile().setExecutable(true, false);
                wrote.add(f.getKey());
            }
            env.out()
                    .println(
                            wrote.isEmpty()
                                    ? "Everything under " + out + " was already up to date."
                                    : "Wrote " + String.join(", ", wrote) + " under " + out);
            if (!kept.isEmpty())
                env.out()
                        .println(
                                "Kept your edits in "
                                        + String.join("; ", kept)
                                        + ". --force replaces them.");
            env.out()
                    .println(
                            "Nothing was built or started. Read "
                                    + out.resolve("README.md")
                                    + " next.");
            return 0;
        } catch (Deploy.Refused e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            env.err().println("Error: " + (e.getMessage() == null ? e : e.getMessage()));
            return 2;
        }
    }

    private static String stem(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static int firstDifference(String a, String b) {
        String[] x = a.split("\n", -1);
        String[] y = b.split("\n", -1);
        for (int i = 0; i < Math.min(x.length, y.length); i++) if (!x[i].equals(y[i])) return i + 1;
        return Math.min(x.length, y.length) + 1;
    }

    /**
     * Fails on a file that is missing, on a generated file that is out of date, and on a
     * hand-edited one that lost a name the script needs.
     */
    private static int verify(Path dir, Map<String, String> expected, DeployPlan plan, WeaveEnv env)
            throws Exception {
        List<String> problems = new ArrayList<>();
        for (var f : expected.entrySet()) {
            Path p = dir.resolve(f.getKey());
            if (!Files.isRegularFile(p)) {
                problems.add("missing: " + f.getKey());
                continue;
            }
            String have = Files.readString(p, StandardCharsets.UTF_8);
            if (have.equals(f.getValue())) continue;
            if (!Deploy.edited(have)) {
                problems.add("stale: " + f.getKey() + " is out of date; run weave deploy again");
                continue;
            }
            // edited by hand: fine, but it must still carry the names the script needs
            if (f.getKey().equals(".env.example") || f.getKey().equals("compose.yaml"))
                for (String n : plan.envNames())
                    if (!have.contains(n))
                        problems.add(
                                "edited: "
                                        + f.getKey()
                                        + " no longer names "
                                        + n
                                        + ", which the script needs");
        }
        if (problems.isEmpty()) {
            env.out().println("The files in " + dir + " match the script.");
            return 0;
        }
        problems.forEach(p -> env.err().println("Error: " + p));
        return 1;
    }

    // ---- the review: a warning, never a gate

    /** True when --require-clean must stop: findings are open, or the review could not say. */
    static boolean blocks(boolean requireClean, Review r) {
        return requireClean && (!r.ran() || r.high() > 0);
    }

    record Review(String warning, String summary, int high, boolean ran) {}

    static Review review(File script, WeaveEnv env) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            WeaveEnv quiet =
                    env.withStreams(
                            new PrintStream(out, true, StandardCharsets.UTF_8),
                            new PrintStream(err, true, StandardCharsets.UTF_8));
            ReviewCommand rc = new ReviewCommand();
            new CommandLine(rc).parseArgs(script.getPath(), "--format", "json", "--no-env");
            if (ReviewCommand.review(rc, quiet) == 2)
                return new Review(null, "The review could not run.", 0, false);
            JsonNode r = new ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8));
            int high = 0, medium = 0, low = 0;
            for (JsonNode f : r.path("findings"))
                switch (f.path("severity").asText()) {
                    case "high" -> high++;
                    case "medium" -> medium++;
                    case "low" -> low++;
                    default -> {}
                }
            StringBuilder s = new StringBuilder();
            if (high + medium + low == 0) s.append("No findings were open.");
            else {
                s.append("Open findings: ")
                        .append(high)
                        .append(" high, ")
                        .append(medium)
                        .append(" medium, ")
                        .append(low)
                        .append(" low.");
                if (high > 0)
                    s.append(
                            " **A high finding is open (for example an unaccepted lethal trifecta): read `weave review` before this goes anywhere that matters.**");
            }
            for (JsonNode a : r.path("accepted"))
                s.append("\n\nAccepted: `")
                        .append(a.path("rule").asText())
                        .append("`")
                        .append(
                                a.path("reason").asText("").isEmpty()
                                        ? ""
                                        : " — " + a.path("reason").asText());
            String warning =
                    high + medium + low == 0
                            ? null
                            : "⚠ weave review: "
                                    + high
                                    + " high · "
                                    + medium
                                    + " medium · "
                                    + low
                                    + " low open — not blocking the deployment files. Details: weave review";
            return new Review(warning, s.toString(), high, true);
        } catch (Exception e) {
            return new Review(null, "The review could not run (" + e.getMessage() + ").", 0, false);
        }
    }
}
