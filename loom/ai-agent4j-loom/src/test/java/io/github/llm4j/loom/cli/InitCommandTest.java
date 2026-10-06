package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.init.Templates;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** R2.1 to R2.5 of loom-onboarding: {@code weave init} makes a project that works, and never overwrites anything. */
class InitCommandTest {

    @TempDir Path dir;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    WeaveEnv env() {
        return new WeaveEnv(m -> { throw new IllegalStateException("no models: a template must check and mock-run without any"); }, q -> "yes",
                new PrintStream(out, true), new PrintStream(err, true), Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), k -> null);
    }

    int init(String... args) {
        InitCommand c = new InitCommand();
        new CommandLine(c).parseArgs(args);
        return InitCommand.init(c, env());
    }

    String stdout() {
        return out.toString();
    }

    private static List<String> files(Path root) throws Exception {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().collect(Collectors.toList());
        }
    }

    // ── the command ──────────────────────────────────────────────────────────

    @Test
    void listNamesTheTemplates() {
        assertThat(init("--list")).isZero();

        assertThat(stdout()).contains("pipeline").contains("approval").contains("classifier").contains("weave init <template> [dir]");
    }

    @Test
    void noTemplateGivenListsThemAndExitsWithAUsageError() {
        assertThat(init()).isEqualTo(2);
        assertThat(stdout()).contains("pipeline");
    }

    @Test
    void anUnknownTemplateIsRefusedWithTheNearestName() {
        assertThat(init("pipelin", dir.toString())).isEqualTo(2);

        assertThat(err.toString()).contains("there is no template named pipelin").contains("did you mean pipeline?").contains("--list");
        assertThat(dir).isEmptyDirectory();
    }

    @Test
    void r2_1_itCreatesTheProjectAndSaysWhatToDoNext() throws Exception {
        Path target = dir.resolve("my-newsletter");

        assertThat(init("pipeline", target.toString())).isZero();

        assertThat(files(target)).contains("main.loom", "README.md", "prompts/writer.md", "eval/golden/workflow.yaml");
        assertThat(stdout()).contains("Created pipeline in").contains("weave check main.loom --no-env").contains("weave eval main.loom --mock");
    }

    @Test
    void theProjectNameIsTheFoldersNameUnlessGiven_andNoPlaceholderIsLeft() throws Exception {
        Path a = dir.resolve("acme-digest");
        Path b = dir.resolve("other");

        init("approval", a.toString());
        init("approval", b.toString(), "--name", "Refund Desk");

        assertThat(Files.readString(a.resolve("main.loom"))).contains("// acme-digest:");
        assertThat(Files.readString(b.resolve("README.md"))).startsWith("# Refund Desk");
        for (Path root : List.of(a, b)) {
            for (String f : files(root)) assertThat(Files.readString(root.resolve(f))).as(f).doesNotContain("{{");
        }
    }

    @Test
    void r2_5_ifAnyFileIsInTheWayNothingIsWrittenAndTheFilesAreNamed() throws Exception {
        Files.writeString(dir.resolve("main.loom"), "mine");

        assertThat(init("classifier", dir.toString())).isEqualTo(2);

        assertThat(err.toString()).contains("nothing was written").contains("main.loom").contains("Choose an empty folder");
        assertThat(files(dir)).containsExactly("main.loom");
        assertThat(Files.readString(dir.resolve("main.loom"))).isEqualTo("mine");
    }

    @Test
    void anExistingFolderThatDoesNotCollideIsFine() throws Exception {
        Files.writeString(dir.resolve("notes.txt"), "keep");

        assertThat(init("classifier", dir.toString())).isZero();

        assertThat(Files.readString(dir.resolve("notes.txt"))).isEqualTo("keep");
        assertThat(files(dir)).contains("main.loom", "notes.txt");
    }

    // ── the templates themselves ─────────────────────────────────────────────

    @Test
    void theListAndTheFilesInTheJarAgree() throws Exception {
        for (Templates.Template t : Templates.all()) {
            Path source = Path.of("src/main/resources/templates", t.name());
            assertThat(files(source)).as(t.name() + " files on disk and in the list").containsExactlyInAnyOrderElementsOf(t.files());
        }
        assertThat(files(Path.of("src/main/resources/templates")).stream().map(f -> f.split("/")[0]).distinct().sorted().toList())
                .isEqualTo(Templates.all().stream().map(Templates.Template::name).sorted().toList());
    }

    @Test
    void r2_4_everyTemplatePassesTheStrictCheckTheAuditTheGraphAndAMockEvaluationWithNoKeysAndNoModel() throws Exception {
        for (Templates.Template t : Templates.all()) {
            Path project = dir.resolve(t.name());
            out.reset();
            assertThat(init(t.name(), project.toString())).as(t.name() + " init").isZero();
            Path main = project.resolve("main.loom");

            out.reset();
            assertThat(WeaveCLI.check(main.toFile(), null, new WeaveCLI.CheckSettings(false, false, true, true), env())).as(t.name() + " check --strict --no-env: " + stdout()).isZero();
            assertThat(stdout()).as(t.name()).contains("ready to run").doesNotContain("⚠").doesNotContain("✗");

            AuditCommand audit = new AuditCommand();
            audit.script = main.toFile();
            audit.format = "md";
            audit.failOn = "medium";
            out.reset();
            assertThat(AuditCommand.audit(audit, env())).as(t.name() + " audit: " + stdout()).isZero();

            out.reset();
            assertThat(io.github.llm4j.loom.cli.CliProbe.graph(main.toFile(), "json", null, new PrintStream(out, true))).as(t.name() + " graph").isZero();
            assertThat(stdout()).contains("\"diagnostics\": [ ]").contains("\"prompt\"");

            out.reset();
            assertThat(EvalCommand.eval(evalCommand(main, "--check"), env())).as(t.name() + " eval --check: " + stdout()).isZero();
            out.reset();
            assertThat(EvalCommand.eval(evalCommand(main, "--mock"), env())).as(t.name() + " eval --mock: " + stdout()).isZero();
            assertThat(stdout()).as(t.name()).contains("0 failed");
        }
    }

    private EvalCommand evalCommand(Path main, String... flags) {
        EvalCommand c = new EvalCommand();
        String[] args = new String[flags.length + 1];
        args[0] = main.toString();
        System.arraycopy(flags, 0, args, 1, flags.length);
        new CommandLine(c).parseArgs(args);
        return c;
    }

    @Test
    void everyTemplateHasPromptsAsFilesAGoldenDatasetAndAReadmeThatSaysHowToRunIt() throws Exception {
        for (Templates.Template t : Templates.all()) {
            Path project = dir.resolve("p-" + t.name());
            init(t.name(), project.toString());
            String readme = Files.readString(project.resolve("README.md"));

            assertThat(files(project)).as(t.name()).anyMatch(f -> f.startsWith("prompts/")).anyMatch(f -> f.startsWith("eval/golden/")).noneMatch(f -> f.endsWith(".java") || f.equals("pom.xml"));
            assertThat(readme).as(t.name()).contains("weave check main.loom --no-env").contains("weave eval main.loom --mock").contains("Evaluation: golden dataset in `eval/golden`")
                    .containsIgnoringCase("no Java here");
            assertThat(Files.readString(project.resolve("main.loom"))).as(t.name()).contains("budget {").contains("prompt: \"");
        }
    }

    @Test
    void r2_5_noTemplateHoldsAKeyOrAKeyShapedString() throws Exception {
        for (Templates.Template t : Templates.all()) {
            Path project = dir.resolve("k-" + t.name());
            init(t.name(), project.toString());
            for (String f : files(project)) {
                assertThat(Files.readString(project.resolve(f))).as(t.name() + "/" + f)
                        .doesNotContainPattern("(?i)(api[_-]?key|secret|token)\\s*[:=]\\s*[\"']?[A-Za-z0-9_\\-]{16,}")
                        .doesNotContainPattern("AIza[0-9A-Za-z_\\-]{20,}")
                        .doesNotContainPattern("sk-[A-Za-z0-9]{20,}");
            }
        }
    }

    @Test
    void theApprovalTemplateReallyAsksAPersonAndTheOthersTellTheTruthAboutWhatTheyDo() throws Exception {
        init("approval", dir.resolve("a").toString());
        String script = Files.readString(dir.resolve("a/main.loom"));

        assertThat(script).contains("human_prompt").contains("guard { pii: mask }");
        assertThat(Files.readString(dir.resolve("a/README.md"))).contains("The model never sends");
    }
}
