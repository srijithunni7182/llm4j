package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.loom.deploy.DeployPlan;
import io.github.llm4j.loom.doctor.Doctor;
import io.github.llm4j.loom.execution.LoomLoader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave doctor}: what is wrong with this setup and the one thing to do about it. Local
 * unless {@code --network}; never shows a secret, only the names of what a script needs.
 */
@Command(
        name = "doctor",
        description =
                "Checks the setup (Java, the script, the keys it needs by name, models, the run store, prices, Fabric) and says the one thing to do for each problem. --network also tests the connection to each provider. Exit 0 ready, 1 ready with warnings, 2 not ready.")
final class DoctorCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "script.loom",
            description = "The script (default: a .loom in this folder).")
    File script;

    @Option(
            names = "--network",
            description =
                    "Also test a TLS connection to each provider host the script uses (no request, no key is sent).")
    boolean network;

    @Option(
            names = "--fix",
            description =
                    "Do the safe local fixes (create the run store folder, ignore .env in git, copy .env.example to .env), asking first.")
    boolean fix;

    @Option(names = "--yes", description = "With --fix: agree without asking.")
    boolean yes;

    @Option(
            names = "--format",
            defaultValue = "text",
            description = "text (default) or json (versioned).")
    String format;

    @Override
    public Integer call() {
        return run(
                this, WeaveEnv.system(), Path.of("").toAbsolutePath(), System::getenv, tlsProbe());
    }

    /** Held so the logger (and its level) is not collected. */
    private static final java.util.logging.Logger QUIET =
            java.util.logging.Logger.getLogger("io.github.llm4j.loom.execution");

    static Doctor.Probe tlsProbe() {
        return (host, port, timeout) -> {
            try (javax.net.ssl.SSLSocket s =
                    (javax.net.ssl.SSLSocket)
                            javax.net.ssl.SSLSocketFactory.getDefault().createSocket()) {
                s.connect(new java.net.InetSocketAddress(host, port), (int) timeout.toMillis());
                s.setSoTimeout((int) timeout.toMillis());
                s.startHandshake();
                return null;
            } catch (java.net.UnknownHostException e) {
                return "unknown host";
            } catch (javax.net.ssl.SSLException e) {
                return "TLS refused";
            } catch (IOException e) {
                return "no connection";
            }
        };
    }

    static int run(
            DoctorCommand c,
            WeaveEnv env,
            Path cwd,
            Function<String, String> vars,
            Doctor.Probe probe) {
        QUIET.setLevel(java.util.logging.Level.OFF);
        try {
            Path script = c.script != null ? c.script.toPath() : findScript(cwd);
            DeployPlan plan = null;
            boolean checked = false;
            String problem = null;
            List<String> unknown = new ArrayList<>();
            if (script != null && Files.isRegularFile(script)) {
                java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
                WeaveEnv quiet =
                        env.withStreams(
                                new java.io.PrintStream(new java.io.ByteArrayOutputStream()),
                                new java.io.PrintStream(err, true));
                int code =
                        WeaveCLI.check(
                                script.toFile(),
                                null,
                                new WeaveCLI.CheckSettings(false, false, true, false),
                                quiet);
                checked = true;
                if (code != 0) {
                    String text = err.toString(java.nio.charset.StandardCharsets.UTF_8);
                    problem =
                            text.lines()
                                    .map(String::strip)
                                    .filter(l -> !l.isEmpty() && !l.startsWith("Error: the script"))
                                    .findFirst()
                                    .orElse("it does not check");
                    for (String l : text.split("\n")) {
                        java.util.regex.Matcher m =
                                java.util.regex.Pattern.compile("unknown model \"([^\"]+)\"")
                                        .matcher(l);
                        if (m.find() && !unknown.contains(m.group(1))) unknown.add(m.group(1));
                    }
                }
                try {
                    plan =
                            DeployPlan.derive(
                                    new LoomLoader().load(script.toString()),
                                    script.getFileName().toString());
                    for (String m : plan.unknownModels()) if (!unknown.contains(m)) unknown.add(m);
                } catch (Exception e) {
                    // a script that does not load is reported by the project check
                }
            } else script = null;
            Doctor.Context ctx =
                    new Doctor.Context(
                            script,
                            plan,
                            checked,
                            problem,
                            unknown,
                            vars,
                            cwd,
                            Runtime.version().feature(),
                            System.getProperty("java.home"),
                            vars.apply("JAVA_HOME"),
                            version(),
                            Instant.now(),
                            c.network,
                            probe,
                            script == null ? List.of() : EnvExampleCheck.namesWithValues(script));
            if (c.network && plan != null && !plan.egressHosts().isEmpty())
                env.err()
                        .println(
                                "Testing a TLS connection (no request, no key) to: "
                                        + String.join(", ", plan.egressHosts()));
            List<Doctor.Check> checks = Doctor.run(ctx);
            if (c.format.equals("json")) env.out().println(json(checks));
            else text(checks, env);
            if (c.fix && !c.format.equals("json")) fixes(c, env, cwd, script);
            return Doctor.exitCode(checks);
        } catch (Exception e) {
            env.err().println("Error: " + (e.getMessage() == null ? e : e.getMessage()));
            return 2;
        }
    }

    private static Path findScript(Path cwd) throws IOException {
        Path main = cwd.resolve("main.loom");
        if (Files.isRegularFile(main)) return main;
        try (var s = Files.list(cwd)) {
            return s.filter(p -> p.toString().endsWith(".loom")).sorted().findFirst().orElse(null);
        }
    }

    private static String version() {
        String v = DoctorCommand.class.getPackage().getImplementationVersion();
        return v == null ? "(development build)" : v;
    }

    private static void text(List<Doctor.Check> checks, WeaveEnv env) {
        for (Doctor.Check k : checks) {
            String mark =
                    switch (k.status()) {
                        case OK -> "ok   ";
                        case WARN -> "warn ";
                        case FAIL -> "FAIL ";
                        case SKIPPED -> "skip ";
                    };
            env.out().println(mark + String.format("%-8s", k.name()) + k.reason());
            if (!k.action().isEmpty() && k.status() != Doctor.Status.OK)
                env.out().println("      → " + k.action());
        }
        env.out().println();
        env.out().println(Doctor.verdict(checks));
    }

    static String json(List<Doctor.Check> checks) throws Exception {
        ObjectMapper m = new ObjectMapper();
        ObjectNode root = m.createObjectNode();
        root.put("version", 1);
        root.put("verdict", Doctor.verdict(checks));
        ArrayNode arr = root.putArray("checks");
        for (Doctor.Check k : checks)
            arr.addObject()
                    .put("name", k.name())
                    .put("status", k.status().name().toLowerCase())
                    .put("reason", k.reason())
                    .put("action", k.action());
        return m.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private static void fixes(DoctorCommand c, WeaveEnv env, Path cwd, Path script)
            throws IOException {
        Path base = script != null ? script.toAbsolutePath().getParent() : cwd;
        List<String> todo = new ArrayList<>();
        if (!Files.isDirectory(cwd.resolve(".loom-triggers")))
            todo.add("create the run store folder .loom-triggers");
        Path gi = base.resolve(".gitignore");
        if (Files.isDirectory(base.resolve(".git"))
                && !(Files.isRegularFile(gi)
                        && Files.readString(gi).lines().anyMatch(l -> l.strip().equals(".env"))))
            todo.add("add .env to .gitignore");
        if (Files.isRegularFile(base.resolve(".env.example"))
                && !Files.exists(base.resolve(".env")))
            todo.add("copy .env.example to .env (you fill in the values)");
        if (todo.isEmpty()) {
            env.out().println("Nothing to fix here.");
            return;
        }
        env.out().println("Safe local fixes: " + String.join("; ", todo) + ".");
        boolean go = c.yes;
        if (!go) {
            String a;
            try {
                a = env.human().promptHuman("Apply them? (yes/no)");
            } catch (RuntimeException e) {
                a = "no";
            }
            go = a != null && a.trim().equalsIgnoreCase("yes");
        }
        if (!go) {
            env.out().println("Nothing was changed.");
            return;
        }
        if (todo.stream().anyMatch(t -> t.contains("run store")))
            Files.createDirectories(cwd.resolve(".loom-triggers"));
        if (todo.stream().anyMatch(t -> t.contains(".gitignore")))
            Files.writeString(
                    gi,
                    (Files.isRegularFile(gi) ? Files.readString(gi).stripTrailing() + "\n" : "")
                            + ".env\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        if (todo.stream().anyMatch(t -> t.contains("copy .env.example")))
            Files.copy(base.resolve(".env.example"), base.resolve(".env"));
        env.out().println("Done.");
    }
}
