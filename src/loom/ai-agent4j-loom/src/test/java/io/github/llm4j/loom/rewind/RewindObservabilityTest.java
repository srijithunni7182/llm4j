package io.github.llm4j.loom.rewind;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.RunTravel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a run reports about going back, and that the new words do not disturb existing scripts (spec loom-rewind-and-fork R7, R8.3, R9.1). */
class RewindObservabilityTest {

    @TempDir
    Path dir;

    @Test
    @Tag("RW-V7.2")
    @Tag("RW-V7.3")
    void everyStageIsAuditedWithItsDetailsAndTheScriptCanReadItsAttempt() {
        ReportScript model = new ReportScript(5, 5, 5); // two rewinds, then the handler
        ScriptedRun run = new ScriptedRun(dir);
        run.journal = RunJournal.inMemory();
        run.responder = model::reply;
        var executor = run.executor(ReportScript.SCRIPT.replace("delegate \"Write the report from {analysis}\" to Writer -> draft",
                "delegate \"Write the report from {analysis} (attempt {_generation})\" to Writer -> draft"));
        executor.initialize();
        executor.executeWorkflow("Report", Map.of("topic", "bees"));

        assertThat(run.audit).contains("checkpoint_reached", "run_rewound", "rewind_exhausted");
        int at = run.audit.indexOf("run_rewound");
        Map<String, Object> rewound = run.auditData.get(at);
        assertThat(rewound).containsKeys("statement", "checkpoint", "generation");
        assertThat(rewound.get("checkpoint")).isEqualTo("collected");
        assertThat(rewound.get("statement")).isEqualTo("Report/s5");
        Map<String, Object> checkpoint = run.auditData.get(run.audit.indexOf("checkpoint_reached"));
        assertThat(checkpoint).containsEntry("checkpoint", "collected");
        assertThat(run.trace.stream().filter(t -> t.type().equals("rewind")).map(t -> t.text()).toList())
                .hasSize(2).allMatch(t -> t.contains("rewind to collected") && t.contains("generation"));
        assertThat(model.tasks.stream().filter(t -> t.startsWith("Writer")).reduce((a, b) -> b).orElse("")).contains("attempt 3");
    }

    @Test
    @Tag("RW-V4.9")
    void theTraceOfARewindListsTheApprovedCallsThatRanAndWillNotBeAskedAgain() {
        ScriptedRun run = new ScriptedRun(dir);
        run.journal = RunJournal.inMemory();
        run.responder = r -> {
            String system = r.getMessages().get(0).getContent();
            String message = ScriptedRun.lastMessage(r);
            if (system.contains("Reviewer")) return "```json\n{\"score\": " + (message.contains("sent(fix") ? 9 : 1) + ", \"notes\": \"fix1\"}\n```";
            String tail = message.substring(Math.max(0, message.lastIndexOf("Send. Feedback")));
            if (tail.contains("Observation:")) return ScriptedRun.done("sent(" + (tail.contains("Feedback: fix1") ? "fix1" : "none") + ")");
            return ScriptedRun.call("Log", "{\"action\": \"append\", \"path\": \"n.md\", \"content\": \"hello\"}");
        };
        var executor = run.executor("""
                tool Log { use: file  root: "out"  mode: write }
                agent Sender { model: "m" system: "You are Sender." tools: [Log] approve: [Log] max_iterations: 6 }
                agent Reviewer { model: "m" system: "You are Reviewer." }
                workflow W() {
                    checkpoint a  starting with feedback = "none"
                    delegate "Send. Feedback: {feedback}" to Sender -> sent
                    delegate "Review {sent}" to Reviewer -> review expecting { score: number, notes: string }
                    rewind to a when (review.score < 7) at most 1 time carrying feedback = "{review.notes}" side effects: keep
                }
                """);
        executor.initialize();
        executor.executeWorkflow("W", Map.of());

        assertThat(run.trace.stream().filter(t -> t.type().equals("rewind")).map(t -> t.text()).toList())
                .anyMatch(t -> t.contains("approved calls that ran and will not be asked again: Log at W/s1"));
    }

    @Test
    @Tag("RW-V8.3")
    void whatAPersonIsShownOfARewindHasItsControlCharactersNeutralised() {
        RunJournal journal = RunJournal.inMemory();
        var generations = new io.github.llm4j.loom.runtime.Generations(journal);
        generations.start("W/s", 0, "start\nline", "operator:ada x", null, "bad\u0007reason", Map.of("k", "v\r\nforged: line"), "keep", false);

        String text = RunTravel.timeline(journal).text();

        assertThat(text).doesNotContain("\r").doesNotContain(" ").doesNotContain("\u0007");
        assertThat(text.lines().filter(l -> l.startsWith("forged"))).isEmpty();
        assertThat(RunTravel.neutralise("a\tb\u0085c")).isEqualTo("a?b?c");
        assertThat(RunTravel.neutralise("plain text é")).isEqualTo("plain text é");
    }

    @Test
    @Tag("RW-V9.1")
    void theNewWordsAreKeywordsOnlyWhereAStatementStartsSoEveryScriptInTheRepositoryStillParses() throws Exception {
        Path repo = Path.of("../../..").toAbsolutePath().normalize();
        List<Path> scripts;
        try (Stream<Path> all = Files.walk(repo)) {
            scripts = all.filter(p -> p.toString().endsWith(".loom"))
                    .filter(p -> !p.toString().contains("/target/") && !p.toString().contains("/node_modules/")).sorted().toList();
        }
        assertThat(scripts).hasSizeGreaterThan(5);
        Pattern result = Pattern.compile("->\\s*([A-Za-z_]\\w*)");
        int renamed = 0;
        for (Path script : scripts) {
            String text = Files.readString(script);
            try {
                new LoomParser(new Lexer(text).tokenize()).parseScript();
            } catch (RuntimeException notAWholeScript) {
                continue; // a fragment meant to be imported: the parser tests' business
            }
            // every variable a script assigns is called "checkpoint" (and then "rewind"), wherever it is mentioned
            for (String word : List.of("checkpoint", "rewind")) {
                Matcher m = result.matcher(text);
                String changed = text;
                while (m.find()) {
                    String name = m.group(1);
                    if (name.equals(word) || name.length() < 2) continue;
                    changed = changed.replaceAll("\\{" + Pattern.quote(name) + "(\\.[\\w.]+)?\\}", "{" + word + "$1}").replaceAll("->\\s*" + Pattern.quote(name) + "\\b", "-> " + word);
                    renamed++;
                }
                try {
                    new LoomParser(new Lexer(changed).tokenize()).parseScript();
                } catch (RuntimeException e) {
                    throw new AssertionError(script + " no longer parses with its variables named " + word + ": " + e.getMessage(), e);
                }
            }
        }
        assertThat(renamed).as("variables renamed across the repository's scripts").isGreaterThan(5);
        // and as the name of an agent, a tool and a workflow parameter
        new LoomParser(new Lexer("""
                tool rewind { use: calculator }
                agent checkpoint { model: "m" system: "s" tools: [rewind] }
                workflow W(rewind, checkpoint) { delegate "{rewind} {checkpoint}" to checkpoint -> rewind }
                """).tokenize()).parseScript();
        List<String> unused = new ArrayList<>();
        assertThat(unused).isEmpty();
    }
}
