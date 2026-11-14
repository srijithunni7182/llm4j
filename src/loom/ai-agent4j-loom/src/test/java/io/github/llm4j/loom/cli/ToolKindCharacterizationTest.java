package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.UseStmt;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Phase 35, guard G36: moving what a tool kind reaches out of Loom's lists and into the kinds
 * themselves changes nothing a user sees. For every {@code .loom} file in the repository that
 * loads, it records what {@code weave audit} (json), {@code weave review} (json, every severity)
 * and the load-time checks behind {@code weave check} (every problem, warnings included) say, and
 * compares a digest of each with the one recorded before the refactor ({@link #GOLDEN}).
 *
 * <p>Any difference is a finding for the owner (D2), not something to make green by recording
 * again: it is either a kind whose profile is wrong or a place where the old lists disagreed. The
 * full current outputs are written to {@code target/characterization/} so the difference can be
 * read. {@code -Dcharacterization.record=true} writes the golden file; it is used once, before the
 * refactor, and again only when the owner accepts a difference.
 */
class ToolKindCharacterizationTest {

    private static final Path REPO = Path.of("../../..").toAbsolutePath().normalize();
    private static final Path GOLDEN =
            Path.of("src/test/resources/characterization/tool-kinds.tsv");
    private static final Path OUTPUTS = Path.of("target/characterization");

    @Test
    void auditReviewAndCheckSayTheSameForEveryScriptInTheRepository() throws Exception {
        Map<String, String> now = new LinkedHashMap<>();
        Files.createDirectories(OUTPUTS);
        for (Path script : scripts()) {
            String name = REPO.relativize(script).toString().replace('\\', '/');
            LoomScript parsed;
            try {
                parsed = new LoomLoader().load(script.toString());
            } catch (Exception e) {
                continue; // fragments and deliberately broken fixtures do not load
            }
            String audit = normalize(canonical(audit(script)));
            String review = normalize(canonical(review(script)));
            String check = normalize(check(script, parsed));
            Path out = OUTPUTS.resolve(name.replace('/', '_') + ".txt");
            Files.writeString(
                    out, "== audit\n" + audit + "\n== review\n" + review + "\n== check\n" + check);
            now.put(name, digest(audit) + "\t" + digest(review) + "\t" + digest(check));
        }
        assertThat(now).hasSizeGreaterThan(100);

        StringBuilder table = new StringBuilder("# script\taudit\treview\tcheck\n");
        now.forEach((k, v) -> table.append(k).append('\t').append(v).append('\n'));
        if (Boolean.getBoolean("characterization.record")) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, table);
            return;
        }
        Map<String, String> recorded = new LinkedHashMap<>();
        for (String line : Files.readAllLines(GOLDEN)) {
            if (line.startsWith("#") || line.isBlank()) continue;
            int tab = line.indexOf('\t');
            recorded.put(line.substring(0, tab), line.substring(tab + 1));
        }
        List<String> differences = new ArrayList<>();
        recorded.forEach(
                (script, digests) -> {
                    String current = now.get(script);
                    if (current == null) differences.add(script + ": no longer loads");
                    else if (!current.equals(digests))
                        differences.add(script + ": " + channels(digests, current));
                });
        now.keySet().stream()
                .filter(s -> !recorded.containsKey(s))
                .forEach(s -> differences.add(s + ": not in the recorded set"));
        assertThat(differences)
                .as("outputs that differ from the recording (full text in " + OUTPUTS + ")")
                .isEmpty();
    }

    /** Which of audit, review and check differ. */
    private static String channels(String recorded, String current) {
        String[] a = recorded.split("\t");
        String[] b = current.split("\t");
        String[] names = {"audit", "review", "check"};
        List<String> out = new ArrayList<>();
        for (int i = 0; i < names.length; i++) if (!a[i].equals(b[i])) out.add(names[i]);
        return String.join(", ", out) + " changed";
    }

    /** Every .loom file in the repository, fixtures that are wrong on purpose included. */
    static List<Path> scripts() throws Exception {
        try (Stream<Path> all = Files.walk(REPO)) {
            return all.filter(p -> p.toString().endsWith(".loom") && Files.isRegularFile(p))
                    .filter(
                            p ->
                                    !p.toString().contains("/target/")
                                            && !p.toString().contains("/node_modules/")
                                            && !p.toString().contains("/.git/"))
                    .sorted()
                    .toList();
        }
    }

    private static WeaveEnv env(ByteArrayOutputStream out) {
        return new WeaveEnv(
                m -> null,
                q -> "",
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                Clock.systemUTC(),
                d -> {},
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"),
                ToolKindCharacterizationTest::envValue);
    }

    private static String audit(Path script) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AuditCommand c = new AuditCommand();
        new CommandLine(c).parseArgs(script.toString(), "--format", "json", "--fail-on", "none");
        int code = AuditCommand.audit(c, env(out));
        return "exit " + code + "\n" + out.toString(StandardCharsets.UTF_8);
    }

    private static String review(Path script) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ReviewCommand c = new ReviewCommand();
        new CommandLine(c)
                .parseArgs(
                        script.toString(),
                        "--format",
                        "json",
                        "--fail-on",
                        "none",
                        "--min-severity",
                        "info");
        int code = ReviewCommand.review(c, env(out));
        return "exit " + code + "\n" + out.toString(StandardCharsets.UTF_8);
    }

    /** Every load-time problem, set up as the repository scripts test sets up a host. */
    private static String check(Path script, LoomScript parsed) throws Exception {
        ToolRegistry hostTools = new ToolRegistry();
        for (AgentDef a : parsed.getAgents())
            a.getTools()
                    .forEach(
                            t -> hostTools.register(t, new io.github.llm4j.agent.tools.EchoTool()));
        for (var w : parsed.getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    st -> {
                        if (st instanceof UseStmt u)
                            hostTools.register(
                                    u.getTool(), new io.github.llm4j.agent.tools.EchoTool());
                    });
        HarnessExecutor e =
                new HarnessExecutor(
                        parsed,
                        hostTools,
                        m -> {
                            throw new IllegalStateException();
                        });
        io.github.llm4j.agent.task.TaskRegistry hostTasks =
                io.github.llm4j.agent.task.TaskRegistry.discovered();
        for (var w : parsed.getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    st -> {
                        if (st instanceof RunStmt r && !hostTasks.contains(r.getTaskName()))
                            hostTasks.register(
                                    io.github.llm4j.agent.task.Task.pure(
                                            r.getTaskName(),
                                            c -> io.github.llm4j.agent.task.TaskResult.ok()));
                    });
        e.setTaskRegistry(hostTasks);
        e.setBaseDir(script.getParent());
        Path loct = io.github.llm4j.loom.context.ContextFiles.sibling(script, "loct");
        if (Files.isRegularFile(loct)) e.loadCompactors(loct);
        e.setPromptCatalog(io.github.llm4j.loom.prompt.PromptSupport.catalog(parsed, script, null));
        e.setEnvLookup(ToolKindCharacterizationTest::envValue);
        e.setHumanInterface(message -> "");
        e.setEmbeddingFactory(model -> new io.github.llm4j.loom.parity.HashingEmbeddingProvider());
        StringBuilder out = new StringBuilder();
        for (ScriptValidator.Problem p :
                new ScriptValidator().validate(parsed, e.validationContext()))
            out.append(p).append('\n');
        return out.toString();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                    .enable(
                            com.fasterxml.jackson.databind.SerializationFeature
                                    .ORDER_MAP_ENTRIES_BY_KEYS);

    /**
     * The exit line and the report with its object keys sorted: some reports fill a map whose order
     * varies from run to run, and an order is not something a user relies on. Arrays keep their
     * order.
     */
    private static String canonical(String output) throws Exception {
        int newline = output.indexOf('\n');
        String body = output.substring(newline + 1).strip();
        if (body.isEmpty()) return output;
        return output.substring(0, newline + 1)
                + JSON.writeValueAsString(JSON.readValue(body, Object.class));
    }

    /**
     * What every environment variable and secret is set to: a URL, since they feed URL options,
     * except a database's, which must be a JDBC URL for the sql kind's check to pass.
     */
    private static String envValue(String name) {
        return name.equals("DB_URL")
                ? "jdbc:h2:mem:characterization"
                : "https://example.invalid/set-" + name;
    }

    /** Leaves out what depends on where the checkout is. */
    private static String normalize(String text) {
        return text.replace(REPO.toString(), "<repo>");
    }

    private static String digest(String text) throws Exception {
        byte[] d =
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(d, 0, 8);
    }
}
