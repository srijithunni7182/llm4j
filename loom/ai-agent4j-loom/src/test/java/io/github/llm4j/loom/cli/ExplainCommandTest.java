package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.execution.LoomLoader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** weave explain: a script in plain English, from the script alone: the same words every time, no model, no key, nothing run. */
class ExplainCommandTest {

    @TempDir Path dir;

    private record Out(int code, String out, String err) {}

    private Out explain(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
        try {
            List<String> all = new ArrayList<>(List.of("explain"));
            all.addAll(List.of(args));
            return new Out(WeaveCLI.commandLine().execute(all.toArray(String[]::new)), out.toString(), err.toString());
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private Path starter(String template) throws Exception {
        Path project = dir.resolve(template);
        assertThat(WeaveCLI.commandLine().execute("init", template, project.toString())).isZero();
        return project.resolve("main.loom");
    }

    @Test
    void thePipelineStarterIsDescribedStepByStepInPlainWords() throws Exception {
        Out o = explain(starter("pipeline").toString());

        assertThat(o.code()).isZero();
        assertThat(o.out()).contains("Workflows: Main(topic)   Agents: 3")
                .contains("Budget: A run may use 200,000 tokens and 30 model calls.")
                .contains("Researcher: gemini-2.5-flash; temperature 0.3; prompt file \"researcher\"; no tools.")
                .contains("1. Researcher is asked: \"Background and recent news on: {topic}\". The answer is kept as research_notes.")
                .contains("3. Repeat, at most 2 times, until review.verdict is OK:")
                .contains("- If review.verdict is REWRITE:")
                .contains("4. A note is shown: \"Issue ready: {draft_text}\"")
                .contains("4 steps ask a model.").contains("No person is asked anything.").contains("1 loop is bounded by a maximum.");
    }

    @Test
    void whereAPersonIsAskedAndWhatIsMaskedIsSaid() throws Exception {
        String text = explain(starter("approval").toString()).out();

        assertThat(text).contains("personal data mask").contains("A person is asked: \"{why} It needs your approval.")
                .contains("A person is asked at 1 step.").contains("- Otherwise:");
    }

    @Test
    void theSameScriptGivesTheSameWordsEveryTimeWithNoKeysAtAll() throws Exception {
        Path script = starter("classifier");

        String first = explain(script.toString()).out();

        assertThat(explain(script.toString()).out()).isEqualTo(first);
        assertThat(first).isNotBlank();
    }

    @Test
    void everyAgentAndWorkflowIsNamedAndEveryStepHasALine() throws Exception {
        for (String template : List.of("pipeline", "approval", "classifier")) {
            Path script = starter(template);
            LoomScript loaded = new LoomLoader().load(script.toString());
            String text = explain(script.toString()).out();

            loaded.getAgents().forEach(a -> assertThat(text).as(template).contains(a.getName() + ": "));
            loaded.getWorkflows().forEach(w -> assertThat(text).as(template).contains(w.getName() + "("));
            List<String> statements = new ArrayList<>();
            loaded.getWorkflows().forEach(w -> StatementWalker.walk(w.getStatements(), s -> statements.add(s.getClass().getSimpleName())));
            String section = text.substring(text.indexOf(loaded.getWorkflows().get(0).getName() + "(", text.indexOf("\nAgents")), text.indexOf("\nWorth knowing"));
            long lines = section.lines().map(String::strip).filter(l -> l.matches("\\d+\\. .*") || l.startsWith("- "))
                    .filter(l -> !l.startsWith("- Otherwise:") && !l.startsWith("- If it fails:") && !l.startsWith("- If the limit") && !l.startsWith("- If the check")).count();
            assertThat(lines).as(template + " has one line per step").isEqualTo(statements.size());
        }
    }

    @Test
    void oneWorkflowCanBeAskedForAndAnUnknownOneIsRefusedWithTheKnownNames() throws Exception {
        Path script = starter("pipeline");

        assertThat(explain(script.toString(), "--workflow", "Main").code()).isZero();
        Out bad = explain(script.toString(), "--workflow", "Nope");
        assertThat(bad.code()).isEqualTo(2);
        assertThat(bad.err()).contains("no workflow named Nope").contains("Known workflows: Main");
    }

    @Test
    void aScriptThatDoesNotParseSaysSoAndExitsWithTwo() throws Exception {
        Path bad = Files.writeString(dir.resolve("bad.loom"), "workflow {");

        Out o = explain(bad.toString());

        assertThat(o.code()).isEqualTo(2);
        assertThat(o.err()).startsWith("Error:");
        assertThat(o.out()).isEmpty();
    }

    @Test
    void imports_areFollowedSoASplitScriptIsExplainedWhole() throws Exception {
        Files.writeString(dir.resolve("agents.loom"), "agent Helper { model: \"ollama/llama3\" system: \"s\" }\n");
        Files.writeString(dir.resolve("main.loom"), "import \"agents.loom\"\nworkflow Main() {\n    delegate \"hello\" to Helper -> greeting_text\n    note \"{greeting_text}\"\n}\n");

        String text = explain(dir.resolve("main.loom").toString()).out();

        assertThat(text).contains("Helper: ollama/llama3").contains("1. Helper is asked: \"hello\"");
    }
}
