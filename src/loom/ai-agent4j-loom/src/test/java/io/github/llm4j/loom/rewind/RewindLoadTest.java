package io.github.llm4j.loom.rewind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.CheckpointStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RewindStmt;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading and checking checkpoints and rewinds when a script loads (spec loom-rewind-and-fork R1, R2.1, R2.7, R2.9). */
class RewindLoadTest {

    @TempDir
    Path dir;

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private List<String> problems(String source) {
        var executor = new ScriptedRun(dir).executor(source);
        return new ScriptValidator().validate(parse(source), executor.validationContext()).stream().map(ScriptValidator.Problem::toString).collect(Collectors.toList());
    }

    private static String workflow(String body) {
        return """
                agent Writer { model: "m" system: "You are Writer." }
                tool Notify { use: webhook  url: env.HOOK }
                agent Sender { model: "m" system: "You are Sender." tools: [Notify] }
                workflow W() {
                %s
                }
                """.formatted(body);
    }

    @Test
    @Tag("RW-V1.1")
    void checkpointsAndRewindsParseAsSentences() {
        LoomScript script = parse(workflow("""
                checkpoint a
                checkpoint b  starting with x = "1", y = "{topic}"
                delegate "t" to Writer -> r
                rewind to b when (r == "bad") at most 3 times carrying x = "{r}", y = "again"
                    side effects: keep
                    if it still fails { note "gave up" }
                    if blocked { note "held" }
                rewind to start at most 1 time
                """));
        var statements = script.getWorkflows().get(0).getStatements();
        assertThat(statements.get(0)).isInstanceOf(CheckpointStmt.class);
        CheckpointStmt b = (CheckpointStmt) statements.get(1);
        assertThat(b.getStartingWith()).containsEntry("x", "1").containsEntry("y", "{topic}");
        RewindStmt r = (RewindStmt) statements.get(3);
        assertThat(r.getTarget()).isEqualTo("b");
        assertThat(r.getCondition()).isEqualTo("r==bad");
        assertThat(r.getAtMost()).isEqualTo(3);
        assertThat(r.getCarrying()).containsEntry("x", "{r}").containsEntry("y", "again");
        assertThat(r.getEffects()).isEqualTo(RewindStmt.Effects.KEEP);
        assertThat(r.getIfStillFails()).hasSize(1);
        assertThat(r.getIfBlocked()).hasSize(1);
        RewindStmt always = (RewindStmt) statements.get(4);
        assertThat(always.getCondition()).isNull();
        assertThat(always.getEffects()).isEqualTo(RewindStmt.Effects.ASK_FIRST);
    }

    @Test
    @Tag("RW-V1.1")
    void theWordsAreOnlyKeywordsWhereAStatementStarts() {
        LoomScript script = parse("""
                agent checkpoint { model: "m" system: "s" }
                agent rewind { model: "m" system: "s" }
                workflow W(checkpoint) {
                    delegate "go {checkpoint}" to checkpoint -> rewind
                    note "{rewind}"
                }
                """);
        assertThat(script.getAgents()).extracting(a -> a.getName()).containsExactly("checkpoint", "rewind");
    }

    @Test
    @Tag("RW-V2.1")
    void parseErrorsSayWhatToWriteInTheAuthorsOwnWords() {
        assertThatThrownBy(() -> parse(workflow("rewind to a when (x < 1)"))).hasMessageContaining("needs a limit").hasMessageContaining("at most 2 times");
        assertThatThrownBy(() -> parse(workflow("rewind to a at most 0 times"))).hasMessageContaining("at least 1");
        assertThatThrownBy(() -> parse(workflow("rewind to a at most 2"))).hasMessageContaining("at most 2 times");
        assertThatThrownBy(() -> parse(workflow("rewind to a when x < 1 at most 2 times"))).hasMessageContaining("brackets");
        assertThatThrownBy(() -> parse(workflow("rewind to a at most 2 times side effects: sometimes"))).hasMessageContaining("ask first, keep or repeat");
        assertThatThrownBy(() -> parse(workflow("rewind to a at most 2 times if nothing { }"))).hasMessageContaining("if it still fails");
        assertThatThrownBy(() -> parse(workflow("checkpoint a starting with x"))).hasMessageContaining("'='");
    }

    @Test
    @Tag("RW-V1.2")
    void checkpointNamesMustBeUniqueAndNotStart() {
        assertThat(problems(workflow("checkpoint a\ncheckpoint a"))).anyMatch(p -> p.contains("already exists"));
        assertThat(problems(workflow("checkpoint start"))).anyMatch(p -> p.contains("\"start\""));
        assertThat(problems(workflow("checkpoint a\ncheckpoint b"))).noneMatch(p -> p.contains("checkpoint"));
    }

    @Test
    @Tag("RW-V2.6")
    void aRewindMustPointToACheckpointThatComesEarlierInItsOwnBlockOrOneAroundIt() {
        assertThat(problems(workflow("rewind to nowhere at most 1 time"))).anyMatch(p -> p.contains("no checkpoint named nowhere"));
        assertThat(problems(workflow("rewind to later at most 1 time\ncheckpoint later"))).anyMatch(p -> p.contains("must come earlier"));
        assertThat(problems(workflow("alt (1 == 1) { checkpoint inner }\nrewind to inner at most 1 time"))).anyMatch(p -> p.contains("must come earlier"));
        assertThat(problems(workflow("alt (1 == 1) { checkpoint a1 } else { rewind to a1 at most 1 time }"))).anyMatch(p -> p.contains("must come earlier"));

        assertThat(problems(workflow("checkpoint a\nrewind to a at most 1 time"))).noneMatch(p -> p.contains("rewind"));
        assertThat(problems(workflow("rewind to start at most 1 time"))).noneMatch(p -> p.contains("rewind"));
        assertThat(problems(workflow("checkpoint a\nalt (1 == 1) { rewind to a at most 1 time }"))).noneMatch(p -> p.contains("rewind"));
        assertThat(problems(workflow("checkpoint a\nloop until (done == \"yes\") max 2 { rewind to a at most 1 time }"))).noneMatch(p -> p.contains("rewind"));
        assertThat(problems(workflow("checkpoint a\ndelegate \"t\" to Writer -> r on_failure { rewind to a at most 1 time }"))).noneMatch(p -> p.contains("rewind"));
        assertThat(problems(workflow("checkpoint a\nrewind to a at most 1 time if it still fails { rewind to start at most 1 time }"))).noneMatch(p -> p.contains("no checkpoint"));
    }

    @Test
    @Tag("RW-V2.6")
    void aRewindCannotLeaveAParallelBranchOrAForEachBody() {
        String branch = "checkpoint a\nparallel { rewind to a at most 1 time }";
        assertThat(problems(workflow(branch))).anyMatch(p -> p.contains("can't leave the parallel branch"));
        assertThat(problems(workflow("checkpoint a\nparallel for each item in items { rewind to a at most 1 time }"))).anyMatch(p -> p.contains("can't leave"));
        assertThat(problems(workflow("checkpoint a\nparallel for each item in items { rewind to start at most 1 time }"))).anyMatch(p -> p.contains("can't leave"));
        assertThat(problems(workflow("parallel { checkpoint inner\nrewind to inner at most 1 time }"))).noneMatch(p -> p.contains("can't leave") || p.contains("no checkpoint"));
    }

    @Test
    @Tag("RW-V2.12")
    void aConditionIsOneComparisonNotACombination() {
        assertThatThrownBy(() -> parse(workflow("checkpoint a\nrewind to a when (x < 1 or y > 2) at most 1 time"))).hasMessageContaining("one comparison");
        assertThatThrownBy(() -> parse(workflow("checkpoint a\nrewind to a when (x < 1 and y > 2) at most 1 time"))).hasMessageContaining("one comparison");
        assertThat(problems(workflow("checkpoint a\nrewind to a when (x < 1) at most 1 time"))).noneMatch(p -> p.contains("one comparison"));
    }

    @Test
    @Tag("RW-V2.9")
    void stepsThatCanChangeThingsNeedASaidPolicyAndRepeatNeedsApproval() {
        String crossing = "checkpoint a\ndelegate \"send\" to Sender -> r\nrewind to a at most 1 time %s";
        assertThat(problems(workflow(crossing.formatted("")))).anyMatch(p -> p.contains("can change things outside the run") && p.contains("Notify") && p.contains("(warning)"));
        assertThat(problems(workflow(crossing.formatted("side effects: keep")))).noneMatch(p -> p.contains("can change things"));
        assertThat(problems(workflow(crossing.formatted("side effects: repeat")))).anyMatch(p -> p.contains("must be approved"));
        assertThat(problems(workflow("checkpoint a\ndelegate \"w\" to Writer -> r\nrewind to a at most 1 time"))).noneMatch(p -> p.contains("can change things"));
        String approved = workflow(crossing.formatted("side effects: repeat")).replace("tools: [Notify] }", "tools: [Notify] approve: [Notify] }");
        assertThat(problems(approved)).noneMatch(p -> p.contains("must be approved"));
    }
}
