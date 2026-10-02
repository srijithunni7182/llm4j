package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.autonomy.MutableClock;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The new words are keywords only where the grammar expects them; scheduled runs make decisions; old scripts and journals are unaffected (spec loom-earned-autonomy R1.5, R1.7, R10). */
class AutonomyCompatTest {

    @TempDir
    Path dir;

    @Test
    @Tag("EA-V1.7")
    void everyScriptInTheRepositoryStillParsesWithItsVariablesNamedDecisionDecideAndAutonomy() throws Exception {
        Path repo = Path.of("../..").toAbsolutePath().normalize();
        List<Path> scripts;
        try (Stream<Path> all = Files.walk(repo)) {
            scripts = all.filter(p -> p.toString().endsWith(".loom")).filter(p -> !p.toString().contains("/target/") && !p.toString().contains("/node_modules/")).sorted().toList();
        }
        assertThat(scripts).hasSizeGreaterThan(5);
        Pattern result = Pattern.compile("->\\s*([A-Za-z_]\\w*)");
        int renamed = 0;
        for (Path script : scripts) {
            String text = Files.readString(script);
            try {
                new LoomParser(new Lexer(text).tokenize()).parseScript();
            } catch (RuntimeException notAWholeScript) {
                continue;
            }
            for (String word : List.of("decision", "decide", "autonomy")) {
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
        assertThat(renamed).isGreaterThan(5);
        // and as the names of a tool, an agent, a workflow, a parameter and a variable all at once
        var parsed = new LoomParser(new Lexer("""
                tool decision { use: calculator }
                agent autonomy { model: "m" system: "s" tools: [decision] }
                workflow decide(decision, autonomy) {
                    delegate "{decision} {autonomy}" to autonomy -> decide
                    note "{decide}"
                }
                """).tokenize()).parseScript();
        assertThat(parsed.getDecisions()).isEmpty();
        assertThat(parsed.getWorkflows().get(0).getName()).isEqualTo("decide");
    }

    @Test
    @Tag("EA-V1.5")
    void aScheduledRunMakesDecisionsAndLeavesItsCasesInTheStoresLedger() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-01T00:00:00Z"));
        Path store = dir.resolve("store");
        String source = io.github.llm4j.loom.autonomy.Scripts2Access.refund("") + "\nschedule Hourly { every: 1h run: Triage(ticket=\"T-1\", tier=\"gold\", amount=\"20\", reason=\"damaged\", customer_since=\"2020\") }\n";
        Path script = dir.resolve("refund.loom");
        Files.writeString(script, source);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LLMClient client = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                return LLMResponse.builder().content("```json\n{\"choice\": \"approve\", \"reasoning\": \"r\"}\n```").model("m").tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
        WeaveEnv env = new WeaveEnv(m -> client, q -> "approve", new PrintStream(out, true), new PrintStream(out, true), clock, d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), System::getenv);

        assertThat(Triggers.syncSchedules(script, store, env)).isZero();
        clock.advance(Duration.ofHours(2));
        assertThat(WeaveCLI.tick(store, env)).isZero();

        var cases = AutonomySupport.ledger(store).cases("Refund");
        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).verdict()).isEqualTo("approve");
        assertThat(cases.get(0).locator()).contains("runs");
        assertThat(AutonomySupport.levels(store).get("Refund", "gold")).isPresent();
    }

    @Test
    @Tag("EA-V10.2")
    void aScriptAndAJournalFromBeforeThisChangeLoadAndResumeWithTheSameResults() throws Exception {
        // the paused journal the rewind work kept as a legacy sample: a script with no decision and a journal with no autonomy keys
        Path journalFile = dir.resolve("legacy.json");
        Files.copy(Path.of("src/test/resources/legacy/paused-journal.json"), journalFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        io.github.llm4j.loom.runtime.FileRunJournal journal = new io.github.llm4j.loom.runtime.FileRunJournal(journalFile);
        List<String> tasks = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        io.github.llm4j.loom.generic.support.ScriptedRun run = new io.github.llm4j.loom.generic.support.ScriptedRun(dir);
        run.journal = journal;
        run.responder = r -> {
            tasks.add(io.github.llm4j.loom.generic.support.ScriptedRun.lastMessage(r));
            return io.github.llm4j.loom.generic.support.ScriptedRun.done("finished");
        };
        var executor = run.executor("""
                agent A { model: "m" system: "You are A." }
                workflow W() {
                    delegate "first" to A -> prev
                    human_prompt "Go on?" -> go
                    delegate "second, after {prev} and {go}" to A -> next
                }
                """);
        executor.initialize();
        executor.executeWorkflow("W", java.util.Map.of());

        assertThat(Files.readString(Path.of("src/test/resources/legacy/paused-journal.json"))).doesNotContain("decide").doesNotContain("#level");
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0)).contains("second, after legacy-result and yes");
        assertThat(journal.all().keySet()).noneMatch(k -> k.contains("#level") || k.contains("#decide"));
        assertThat(journal.get("W/s0").get().value()).isEqualTo("legacy-result");
        assertThat(journal.get("W/s2").get().value()).isEqualTo("finished");
    }
}
