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

    /** {@code weave init} as the older flat layout (most tests are about what a starter says, not where its files go). */
    int init(String... args) {
        List<String> all = new java.util.ArrayList<>(List.of(args));
        if (!all.contains("--list") && !all.contains("--maven-default")) all.add("--flat");
        all.remove("--maven-default");
        return initAs(all.toArray(String[]::new));
    }

    /** {@code weave init} exactly as typed: the default is a Maven project. */
    int initAs(String... args) {
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
        // the folders in the jar are the templates, and the one overlay that --with-java-tests adds
        List<String> expected = new java.util.ArrayList<>(Templates.all().stream().map(Templates.Template::name).toList());
        expected.add(Templates.JAVA_TESTS);
        assertThat(files(Path.of("src/main/resources/templates")).stream().map(f -> f.split("/")[0]).distinct().sorted().toList()).isEqualTo(expected.stream().sorted().toList());
    }

    @Test
    void r2_4_everyTemplatePassesTheStrictCheckTheAuditTheGraphAndAMockEvaluationWithNoKeysAndNoModel() throws Exception {
        for (Templates.Template t : Templates.all()) {
            if (Templates.mavenOnly(t.name())) continue;   // has Java code: no flat form; WebTemplateTest covers it
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
            if (Templates.mavenOnly(t.name())) continue;
            Path project = dir.resolve("p-" + t.name());
            init(t.name(), project.toString());
            String readme = Files.readString(project.resolve("README.md"));

            assertThat(files(project)).as(t.name()).anyMatch(f -> f.startsWith("prompts/")).anyMatch(f -> f.startsWith("eval/golden/")).noneMatch(f -> f.endsWith(".java") || f.equals("pom.xml"));
            assertThat(readme).as(t.name()).contains("weave check main.loom --no-env").contains("weave eval main.loom --mock").contains("Evaluation: golden dataset in `eval/golden`")
                    .contains("mvn test");
            assertThat(Files.readString(project.resolve("main.loom"))).as(t.name()).contains("budget {").contains("prompt: \"");
        }
    }

    private String captured = "";

    /** The real command line, as a person types it (so --classes and target/classes apply), with what it printed kept in {@code captured}. */
    private int weave(String... args) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        System.setOut(new PrintStream(buffer, true));
        System.setErr(new PrintStream(buffer, true));
        try {
            CommandLine cli = WeaveCLI.commandLine();
            cli.setOut(new java.io.PrintWriter(buffer, true));
            return cli.execute(args);
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
            captured = buffer.toString();
        }
    }

    @Test
    void theWebTemplateIsAMavenProjectWhoseWorkflowChecksAndEvaluatesOnceItsTaskIsBuilt() throws Exception {
        Path project = dir.resolve("web");
        assertThat(init("web", project.toString())).as("--flat is refused: the template has Java code").isEqualTo(2);
        assertThat(initAs("web", project.toString())).as(stdout()).isZero();
        assertThat(files(project)).contains("pom.xml", "run.sh", "src/main/java/web/App.java", "src/main/java/web/SaveMarkdown.java", "src/main/resources/main.loom",
                "src/main/resources/web/index.html", "src/test/java/web/WebHostTest.java", "src/test/resources/eval/golden/workflow.yaml");

        // build it the way `mvn compile` does, so that weave finds the task in target/classes
        Path classes = project.resolve("target/classes");
        java.nio.file.Files.createDirectories(classes);
        java.nio.file.Files.createDirectories(classes.resolve("META-INF/services"));
        java.nio.file.Files.copy(project.resolve("src/main/resources/META-INF/services/io.github.llm4j.agent.task.Task"), classes.resolve("META-INF/services/io.github.llm4j.agent.task.Task"));
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertThat(javac.run(null, null, null, "-cp", System.getProperty("java.class.path"), "-d", classes.toString(),
                project.resolve("src/main/java/web/App.java").toString(), project.resolve("src/main/java/web/Session.java").toString(),
                project.resolve("src/main/java/web/SaveMarkdown.java").toString())).as("the web host compiles against Loom").isZero();

        ClassLoader before = Thread.currentThread().getContextClassLoader();
        try {
            Path main = project.resolve("src/main/resources/main.loom");
            assertThat(weave("check", main.toString(), "--no-env", "--no-env-file")).as("check: " + captured).isZero();
            assertThat(weave("eval", main.toString(), "--mock", "--no-env-file")).as("eval --mock: " + captured).isZero();
            assertThat(captured).contains("0 failed").contains("answer has at least");
        } finally {
            Thread.currentThread().setContextClassLoader(before);
        }
    }

    @Test
    void r2_5_noTemplateHoldsAKeyOrAKeyShapedString() throws Exception {
        for (Templates.Template t : Templates.all()) {
            Path project = dir.resolve("k-" + t.name());
            initAs(t.name(), project.toString());
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

    // ── --with-java-tests (R2.3, R4) ─────────────────────────────────────────

    @Test
    void r2_3_withJavaTestsAddsAMavenModuleAndTheOtherwiseJavaFreeProjectHasNone() throws Exception {
        Path plain = dir.resolve("plain");
        Path withTests = dir.resolve("My Project");

        init("pipeline", plain.toString());
        assertThat(init("pipeline", withTests.toString(), "--with-java-tests")).isZero();

        assertThat(files(plain)).noneMatch(f -> f.equals("pom.xml") || f.endsWith(".java"));
        assertThat(files(withTests)).contains("pom.xml", "src/test/README.md", "src/test/java/starter/GoldenDatasetTest.java", "src/test/java/starter/ScriptWiringTest.java", "main.loom");
        assertThat(stdout()).contains("mvn test").contains("\"Tests run: 0\" is a failure");
        assertThat(Files.readString(withTests.resolve("pom.xml"))).contains("<artifactId>my-project</artifactId>").doesNotContain("{{");
    }

    @Test
    void r4_2_thePomRunsJUnit5AndFailsAnEmptyRun() throws Exception {
        init("classifier", dir.toString(), "--with-java-tests");
        String pom = Files.readString(dir.resolve("pom.xml"));

        assertThat(pom).contains("<artifactId>maven-surefire-plugin</artifactId>").contains("<version>3.2.5</version>").contains("<failIfNoTests>true</failIfNoTests>")
                .contains("<artifactId>junit-jupiter</artifactId>").contains("<artifactId>ai-agent4j-loom</artifactId>");
        assertThat(Files.readString(dir.resolve("src/test/README.md"))).contains("\"Tests run: 0\" is a failure").contains("failIfNoTests");
    }

    @Test
    void theTestsUseTheEvalClassesNotAHandWrittenLoader() throws Exception {
        init("classifier", dir.toString(), "--with-java-tests");
        String dataset = Files.readString(dir.resolve("src/test/java/starter/GoldenDatasetTest.java"));
        String wiring = Files.readString(dir.resolve("src/test/java/starter/ScriptWiringTest.java"));

        assertThat(dataset).contains("DatasetFolder.plan").contains("EvalDataset.load").doesNotContain("Files.newInputStream").doesNotContain("EvalScenarios.fromYaml(");
        assertThat(wiring).contains("MockModels").contains("EvalRunner").contains("Status.FAIL");
    }

    @Test
    void r2_5_aPomAlreadyThereStopsEverythingIncludingTheBaseProject() throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");

        assertThat(init("pipeline", dir.toString(), "--with-java-tests")).isEqualTo(2);

        assertThat(err.toString()).contains("pom.xml");
        assertThat(files(dir)).containsExactly("pom.xml");
    }

    @Test
    void theArtifactIdIsALowerCaseDashedName() {
        assertThat(InitCommand.artifactId("My Project!")).isEqualTo("my-project");
        assertThat(InitCommand.artifactId("acme_digest.v2")).isEqualTo("acme-digest-v2");
        assertThat(InitCommand.artifactId("!!!")).isEqualTo("workflow");
    }

    @Test
    void theOverlayFilesInTheJarAreTheOnesListed() throws Exception {
        assertThat(files(Path.of("src/main/resources/templates/" + Templates.JAVA_TESTS))).containsExactlyInAnyOrderElementsOf(Templates.JAVA_TESTS_FILES);
    }

    // ── the default: a Maven project ──────────────────────────────────────────────

    @Test
    void theDefaultIsAMavenProjectWithTheWorkflowAndPromptsAsMainResourcesAndTheDatasetAsTestResources() throws Exception {
        Path project = dir.resolve("support-replies");
        assertThat(initAs("approval", project.toString())).isZero();

        assertThat(files(project)).contains("pom.xml", "README.md", ".gitignore", ".env.example",
                "src/main/resources/main.loom", "src/main/resources/prompts/triage.md", "src/main/resources/prompts/drafter.md",
                "src/test/resources/eval/golden/dataset.yaml", "src/test/resources/eval/golden/triage.yaml", "src/test/resources/eval/golden/workflow.yaml",
                "src/test/java/starter/GoldenDatasetTest.java", "src/test/java/starter/ScriptWiringTest.java", "src/test/java/starter/Project.java");
        assertThat(files(project)).noneMatch(f -> f.equals("main.loom") || f.startsWith("prompts/") || f.startsWith("eval/"));
        assertThat(stdout()).contains("weave check src/main/resources/main.loom --no-env").contains("mvn test").contains("Where things are:").contains("src/test/resources/eval/golden");
        assertThat(Files.readString(project.resolve("pom.xml"))).contains("<artifactId>support-replies</artifactId>").doesNotContain("{{");
        assertThat(Files.readString(project.resolve(".gitignore"))).contains(".env").contains("target/");
    }

    @Test
    void theReadmeNamesTheRealPathsOfWhicheverLayoutWasMade() throws Exception {
        Path maven = dir.resolve("m");
        Path flat = dir.resolve("f");
        assertThat(initAs("pipeline", maven.toString())).isZero();
        assertThat(initAs("pipeline", flat.toString(), "--flat")).isZero();

        String m = Files.readString(maven.resolve("README.md"));
        assertThat(m).contains("weave check src/main/resources/main.loom --no-env").contains("src/test/resources/eval/golden").contains("src/main/resources/prompts/").doesNotContain("{{");
        String f = Files.readString(flat.resolve("README.md"));
        assertThat(f).contains("weave check main.loom --no-env").contains("`eval/golden/`").contains("`prompts/`").doesNotContain("{{");
    }

    @Test
    void flatKeepsTheOlderFolderAndHasNoJavaUnlessAskedFor() throws Exception {
        Path flat = dir.resolve("f");
        assertThat(initAs("approval", flat.toString(), "--flat")).isZero();
        assertThat(files(flat)).contains("main.loom", "prompts/triage.md", "eval/golden/workflow.yaml").noneMatch(f -> f.equals("pom.xml") || f.endsWith(".java"));
        assertThat(stdout()).contains("weave check main.loom --no-env").doesNotContain("Where things are:");

        Path flatWithTests = dir.resolve("g");
        assertThat(initAs("approval", flatWithTests.toString(), "--flat", "--with-java-tests")).isZero();
        assertThat(files(flatWithTests)).contains("main.loom", "pom.xml", "src/test/java/starter/ScriptWiringTest.java");
    }

    @Test
    void weaveFindsTheDatasetAndTheScriptInAMavenProjectWithNoExtraFlags() throws Exception {
        Path project = dir.resolve("p");
        assertThat(initAs("approval", project.toString())).isZero();
        Path script = project.resolve("src/main/resources/main.loom");
        Path golden = project.resolve("src/test/resources/eval/golden");

        assertThat(io.github.llm4j.loom.eval.DatasetFolder.locate(script, null)).isEqualTo(golden);
        assertThat(io.github.llm4j.loom.init.ProjectLayout.root(script)).isEqualTo(project);
        assertThat(io.github.llm4j.loom.init.ProjectLayout.script(project)).isEqualTo(script);
        assertThat(io.github.llm4j.loom.init.ProjectLayout.shown(script)).isEqualTo("src/main/resources/main.loom");
        assertThat(io.github.llm4j.loom.init.ProjectLayout.envFile(script)).isEqualTo(project.resolve(".env"));

        // a new dataset for a project that has none goes where Maven expects it
        java.nio.file.Files.walk(golden).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        assertThat(io.github.llm4j.loom.eval.DatasetFolder.locate(script, null)).isEqualTo(golden);
    }

    @Test
    void aFlatProjectIsStillFoundWhereItAlwaysWas() throws Exception {
        Path project = dir.resolve("flat");
        assertThat(initAs("approval", project.toString(), "--flat")).isZero();
        Path script = project.resolve("main.loom");

        assertThat(io.github.llm4j.loom.eval.DatasetFolder.locate(script, null)).isEqualTo(project.resolve("eval/golden"));
        assertThat(io.github.llm4j.loom.init.ProjectLayout.root(script)).isEqualTo(project);
        assertThat(io.github.llm4j.loom.init.ProjectLayout.shown(script)).isEqualTo("main.loom");
        assertThat(io.github.llm4j.loom.init.ProjectLayout.envFile(script)).isEqualTo(project.resolve(".env"));
    }
}
