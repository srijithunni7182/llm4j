package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 19 R12 at the command: {@code weave judge validate} reads a scored set and records it. */
class JudgeValidateCommandTest {
    @TempDir Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private int validate(String reference, String... more) throws Exception {
        Path file = Files.writeString(dir.resolve("reference.json"), reference);
        WeaveEnv env =
                new WeaveEnv(
                        m -> null,
                        q -> "yes",
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"),
                        k -> null);
        JudgeCommand.Validate c = new JudgeCommand.Validate();
        String[] args = new String[3 + more.length + 4];
        args[0] = "--reference";
        args[1] = file.toString();
        args[2] = "--model=judge-m";
        System.arraycopy(more, 0, args, 3, more.length);
        args[3 + more.length] = "--project";
        args[4 + more.length] = dir.toString();
        args[5 + more.length] = "--min-outputs";
        args[6 + more.length] = "4";
        new picocli.CommandLine(c).parseArgs(args);
        return JudgeCommand.Validate.validate(c, env);
    }

    private static String set(int judgeShift) {
        StringBuilder b = new StringBuilder("[");
        int[] scores = {1, 2, 3, 4, 5, 2, 4, 3};
        for (int i = 0; i < scores.length; i++) {
            int j = Math.max(1, Math.min(5, scores[i] + (i % 2 == 0 ? judgeShift : -judgeShift)));
            b.append(i == 0 ? "" : ",")
                    .append("{\"id\":\"o")
                    .append(i)
                    .append("\",\"human_1\":")
                    .append(scores[i])
                    .append(",\"human_2\":")
                    .append(scores[i])
                    .append(",\"human_3\":")
                    .append(scores[i])
                    .append(",\"judge\":")
                    .append(j)
                    .append("}");
        }
        return b.append("]").toString();
    }

    private String said() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aJudgeThatAgreesIsUsableAndTheResultIsRecordedForTheReport() throws Exception {
        assertThat(validate(set(0), "--system-model=system-m")).isZero();

        assertThat(said()).contains("Usable for gating");
        assertThat(io.github.llm4j.loom.eval.JudgeValidationRecord.validatedFor(dir.resolve("eval"), "judge-m")).isTrue();
    }

    @Test
    void aJudgeThatDisagreesIsNotUsableAndExitsOne() throws Exception {
        assertThat(validate(set(2))).isEqualTo(1);

        assertThat(said()).contains("not usable for gating").contains("Off by two or more");
        assertThat(io.github.llm4j.loom.eval.JudgeValidationRecord.validatedFor(dir.resolve("eval"), "judge-m")).isFalse();
    }

    @Test
    void tooFewOutputsOrABrokenFileIsAProblemWithTheFileAndExitsTwo() throws Exception {
        assertThat(validate("[{\"id\":\"a\",\"human_1\":1,\"human_2\":1,\"judge\":1}]")).isEqualTo(2);
        assertThat(said()).contains("at least 4");

        assertThat(validate("{ not a list")).isEqualTo(2);
    }

    @Test
    void aJudgeIsNotUsableWhenTheSystemModelWasNotNamed() throws Exception {
        assertThat(validate(set(0))).isEqualTo(1);

        assertThat(said()).contains("Self-preference was not checked").contains("--system-model");
        assertThat(io.github.llm4j.loom.eval.JudgeValidationRecord.validatedFor(dir.resolve("eval"), "judge-m")).isFalse();
    }
}
