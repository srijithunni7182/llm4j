package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** weave next: what to do next, from the files and the free checks. It never calls a model and never changes anything. */
class NextCommandTest {

    @TempDir Path dir;

    private record Out(int code, String out, String err) {}

    private Out next(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
        try {
            String[] all = new String[args.length + 1];
            all[0] = "next";
            System.arraycopy(args, 0, all, 1, args.length);
            return new Out(WeaveCLI.commandLine().execute(all), out.toString(), err.toString());
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private Path starter(String template) {
        Path project = dir.resolve(template);
        assertThat(WeaveCLI.commandLine().execute("init", template, project.toString())).isZero();
        return project;
    }

    private static List<String> numbered(String text) {
        return text.lines().map(String::strip).filter(l -> l.matches("\\d+\\. .*")).toList();
    }

    @Test
    void aFreshStarterHasNothingToFixAndLeadsFreeStepsFirstThenKeysThenTheCappedRealRun() {
        Out o = next(starter("pipeline").toString());

        assertThat(o.code()).isZero();
        List<String> steps = numbered(o.out());
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0)).contains("Run the whole dataset on mock models");
        assertThat(steps.get(1)).contains(".env").contains("GEMINI_API_KEY");
        assertThat(steps.get(2)).contains("Run it for real, capped");
        assertThat(o.out()).contains("$ weave eval main.loom --mock").contains("$ cp .env.example .env").contains("$ weave eval main.loom --max-tokens");
    }

    @Test
    void aScriptThatDoesNotCheckIsTheOnlyThingSaidUntilItIsFixed() throws Exception {
        Path project = starter("pipeline");
        Files.writeString(project.resolve("main.loom"), "workflow Main() {\n    delegate \"x\" to Nobody -> out_text\n    note \"{out_text}\"\n}\n");

        List<String> steps = numbered(next(project.toString()).out());

        assertThat(steps).singleElement().asString().contains("Fix what stops the script from being valid").contains("Nobody");
    }

    @Test
    void aProjectWithNoDatasetIsAskedToDecideAndASkipIsRespected() throws Exception {
        Path project = starter("pipeline");
        deleteTree(project.resolve("eval"));

        List<String> asked = numbered(next(project.toString()).out());
        assertThat(asked.get(0)).contains("Decide about tests");

        Files.writeString(project.resolve("README.md"), "# x\n\nEvaluation: skipped\n");
        String after = next(project.toString()).out();
        assertThat(after).doesNotContain("Decide about tests").doesNotContain("mock models");
        assertThat(after).contains("Run it once for real, capped").contains("$ weave run main.loom --max-tokens");
    }

    @Test
    void anAgentWithNoCasesIsNamedAndTheDatasetCommandIsGiven() throws Exception {
        Path project = starter("pipeline");
        Files.delete(project.resolve("eval/golden/writer.yaml"));

        String text = next(project.toString()).out();

        assertThat(numbered(text).get(0)).contains("Add cases for the agent with none: Writer");
        assertThat(text).contains("$ weave eval main.loom --init");
    }

    @Test
    void aMissingBudgetIsSaid() throws Exception {
        Path project = starter("pipeline");
        String script = Files.readString(project.resolve("main.loom")).replaceFirst("budget \\{[^}]*}\\n", "");
        Files.writeString(project.resolve("main.loom"), script);

        String text = next(project.toString()).out();

        assertThat(text).contains("Set a budget at the top of the script");
    }

    @Test
    void aSingleScriptInAFolderIsFoundAndSeveralAreAskedWhich() throws Exception {
        Files.writeString(dir.resolve("only.loom"), "agent A { model: \"ollama/llama3\" system: \"s\" }\nbudget { tokens: 100 calls: 2 }\nworkflow Main() { delegate \"hi\" to A -> hello_text\n note \"{hello_text}\" }\n");
        assertThat(next(dir.toString()).out()).startsWith("weave next: only.loom");

        Files.writeString(dir.resolve("two.loom"), "workflow Main() { note \"x\" }\n");
        Out many = next(dir.toString());
        assertThat(many.code()).isEqualTo(2);
        assertThat(many.err()).contains("more than one .loom file").contains("only.loom, two.loom");
    }

    @Test
    void aFolderWithNoScriptPointsAtInit() {
        Out o = next(dir.toString());

        assertThat(o.code()).isEqualTo(2);
        assertThat(o.err()).contains("no .loom file").contains("weave init");
    }

    @Test
    void itChangesNothingAndNeedsNoKeys() throws Exception {
        Path project = starter("approval");
        List<String> before = listing(project);

        next(project.toString());

        assertThat(listing(project)).isEqualTo(before);
    }

    private static List<String> listing(Path root) throws Exception {
        try (var s = Files.walk(root)) {
            return s.map(p -> root.relativize(p) + ":" + (Files.isRegularFile(p) ? p.toFile().length() : -1)).sorted().toList();
        }
    }

    private static void deleteTree(Path root) throws Exception {
        try (var s = Files.walk(root)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
