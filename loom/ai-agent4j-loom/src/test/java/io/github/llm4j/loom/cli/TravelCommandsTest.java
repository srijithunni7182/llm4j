package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.rewind.ReportScript;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The operator's commands on a run's history: timeline, rewind, reset, fork and stopping at a step (spec loom-rewind-and-fork R5). */
class TravelCommandsTest {

    @TempDir
    Path dir;

    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    ReportScript model;
    File script;
    Path run;

    @BeforeEach
    void setUp() throws IOException {
        script = dir.resolve("report.loom").toFile();
        Files.writeString(script.toPath(), "budget { tokens: 1000000 }\n" + ReportScript.SCRIPT);
        run = dir.resolve("runs/one");
        model = new ReportScript(5, 6, 8);
    }

    String out() {
        return outBytes.toString();
    }

    String err() {
        return errBytes.toString();
    }

    WeaveEnv env() {
        LLMClient client = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                return LLMResponse.builder().content(model.reply(request)).model("m").tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
        return new WeaveEnv(m -> client, message -> "yes", new PrintStream(outBytes, true), new PrintStream(errBytes, true), Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), System::getenv);
    }

    int start(String stopAt) {
        return WeaveCLI.run(script, null, "Report", Map.of("topic", "bees"), null, null, null, null, run, null, false, false, null, stopAt, env());
    }

    @Test
    @Tag("RW-V5.1")
    void theTimelineShowsStepsByAttemptTheirStateTheRewindsAndTheSpendSplit() throws Exception {
        assertThat(start(null)).isZero();
        outBytes.reset();
        assertThat(TravelCommands.show(run, false, new PrintStream(outBytes, true), System.err)).isZero();

        String text = out();
        assertThat(text).contains("Report/s1 ").contains("Report/s1~2").contains("Report/s1~3");
        assertThat(text).contains("discarded").contains("done");
        assertThat(text).contains("Checkpoints reached: collected").contains("Rewinds:").contains("generation 2").contains("generation 3")
                .contains("review.score<7").contains("side effects: ask first").contains("carrying {feedback=fix1}");
        assertThat(text).containsPattern("Spend: kept \\d+ tokens");
        assertThat(text).doesNotContain("Spend: kept 0 tokens");

        outBytes.reset();
        assertThat(TravelCommands.show(run, true, new PrintStream(outBytes, true), System.err)).isZero();
        assertThat(out()).contains("\"steps\"").contains("\"boundaries\"").contains("\"discardedTokens\"");
    }

    @Test
    @Tag("RW-V5.9")
    void aRunCanBeToldToStopAfterAStepAndResumedLater() {
        assertThat(start("collected")).isEqualTo(5);
        assertThat(out()).contains("Stopped at collected");
        assertThat(model.count("Collector")).isZero();

        assertThat(WeaveCLI.resume(run, env())).isZero();
        assertThat(model.count("Publisher")).isEqualTo(1);
    }

    @Test
    @Tag("RW-V5.2")
    @Tag("RW-V5.3")
    void anOperatorRewindStartsANewAttemptAndResumeRunsIt() throws Exception {
        assertThat(start(null)).isZero();
        long collectors = model.count("Collector");
        long publishers = model.count("Publisher");

        int code = TravelCommands.rewind(run, "collected", Map.of("feedback", "by-hand"), null, false, "the sources were stale", true, false, env());

        assertThat(code).isZero();
        assertThat(model.count("Collector")).isEqualTo(collectors + 1);
        assertThat(model.count("Publisher")).isEqualTo(publishers + 1);
        assertThat(model.tasks.stream().filter(t -> t.startsWith("Collector")).reduce((a, b) -> b).orElse("")).contains("Feedback so far: by-hand");
        var boundaries = new io.github.llm4j.loom.runtime.Generations(new FileRunJournal(run.resolve("journal.json"))).all();
        assertThat(boundaries.get(boundaries.size() - 1).by()).startsWith("operator:");
        assertThat(boundaries.get(boundaries.size() - 1).reason()).isEqualTo("the sources were stale");
        assertThat(Files.readString(run.resolve("operator-audit.jsonl"))).contains("run_rewound").contains("the sources were stale");
    }

    @Test
    @Tag("RW-V5.2")
    void aTargetThatIsNotAPlaceTheRunHasBeenToIsRefusedWithTheValidOnes() throws Exception {
        assertThat(start(null)).isZero();
        int code = TravelCommands.rewind(run, "nowhere", Map.of(), null, false, "r", false, false, env());
        assertThat(code).isEqualTo(2);
        assertThat(err()).contains("not a checkpoint").contains("collected").contains("start").contains("Report/s1");

        errBytes.reset();
        assertThat(TravelCommands.rewind(run, "Report/s9", Map.of(), null, false, "r", false, false, env())).isEqualTo(2); // a statement the run never reached
    }

    @Test
    @Tag("RW-V5.2")
    void aRunNeedsSomeoneToHoldTheLockForAChangeToBeRefusedAndForceOverridesIt() throws Exception {
        assertThat(start(null)).isZero();
        Files.writeString(run.resolve("run.lock"), String.valueOf(ProcessHandle.current().pid()));

        assertThat(TravelCommands.rewind(run, "collected", Map.of(), null, false, "r", false, false, env())).isEqualTo(2);
        assertThat(err()).contains("is working on this run");

        assertThat(TravelCommands.rewind(run, "collected", Map.of(), null, false, "forcing", false, true, env())).isZero();
        assertThat(Files.readString(run.resolve("operator-audit.jsonl"))).contains("\"forced\":true");

        Files.writeString(run.resolve("run.lock"), "999999999"); // no such process: a stale lock is ignored
        assertThat(TravelCommands.rewind(run, "collected", Map.of(), null, false, "stale lock", false, false, env())).isZero();
    }

    @Test
    @Tag("RW-V5.5")
    @Tag("RW-V5.6")
    void aForkIsACopyThatNeverChangesTheOriginal() throws Exception {
        assertThat(start(null)).isZero();
        byte[] before = Files.readAllBytes(run.resolve("journal.json"));
        Path child = dir.resolve("runs/two");

        int code = TravelCommands.fork(run, child, "collected", null, Map.of("feedback", "forked"), null, null, false, "try it", true, env());

        assertThat(code).isZero();
        assertThat(Files.readAllBytes(run.resolve("journal.json"))).isEqualTo(before);
        assertThat(Files.readString(child.resolve("run.json"))).contains("forkOf").contains("try it");
        assertThat(new FileRunJournal(child.resolve("journal.json")).all().size()).isGreaterThan(new FileRunJournal(run.resolve("journal.json")).all().size());
        assertThat(model.tasks.stream().filter(t -> t.startsWith("Collector")).reduce((a, b) -> b).orElse("")).contains("Feedback so far: forked");
        assertThat(Files.readString(child.resolve("operator-audit.jsonl"))).contains("run_forked");

        assertThat(TravelCommands.fork(run, child, null, null, Map.of(), null, null, false, "again", false, env())).isEqualTo(2); // the directory is taken
    }

    @Test
    @Tag("RW-V5.7")
    void aForkUnderAnotherScriptIsRefusedWhenTheFinishedPartDiffersUnlessAllowed() throws Exception {
        assertThat(start(null)).isZero();
        File changedBefore = dir.resolve("changed-before.loom").toFile();
        Files.writeString(changedBefore.toPath(), ReportScript.SCRIPT.replace("Analyse {data}", "Analyse harder {data}"));
        File changedAfter = dir.resolve("changed-after.loom").toFile();
        Files.writeString(changedAfter.toPath(), ReportScript.SCRIPT.replace("Publish {draft}", "Publish loudly {draft}"));

        assertThat(TravelCommands.fork(run, dir.resolve("runs/a"), "collected", changedAfter, Map.of(), null, null, false, "ok", false, env())).isZero();

        assertThat(TravelCommands.fork(run, dir.resolve("runs/b"), "Report/s4", changedBefore, Map.of(), null, null, false, "drift", false, env())).isEqualTo(2);
        assertThat(err()).contains("differ before the fork point").contains("Analyse");

        errBytes.reset();
        assertThat(TravelCommands.fork(run, dir.resolve("runs/c"), "Report/s4", changedBefore, Map.of(), null, null, true, "drift ok", false, env())).isZero();
        assertThat(Files.readString(dir.resolve("runs/c/run.json"))).contains("\"drift\" : \"accepted\"");
    }

    @Test
    @Tag("RW-V5.4")
    void resetStartsOverAsANewAttemptAndKeepsTheSpendHistory() throws Exception {
        assertThat(start(null)).isZero();
        long publishers = model.count("Publisher");
        Path journal = run.resolve("journal.json");
        int before = new FileRunJournal(journal).all().size();

        int code = TravelCommands.reset(run, false, "keep", "start over", true, false, env());

        assertThat(code).isZero();
        assertThat(model.count("Publisher")).isEqualTo(publishers + 1); // everything ran again, as a new attempt
        assertThat(new FileRunJournal(journal).all().size()).isGreaterThan(before);
        assertThat(new io.github.llm4j.loom.runtime.Generations(new FileRunJournal(journal)).all()).anyMatch(b -> "start".equals(b.name()));
        assertThat(Files.readString(run.resolve("operator-audit.jsonl"))).contains("run_reset");
    }
}
