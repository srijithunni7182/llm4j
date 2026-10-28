package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.runtime.NoHumanAvailable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A question for a person on a console that is closed (a pipe, a scheduler) is said plainly; it is
 * not a stack trace or an empty answer.
 */
class ConsoleClosedTest {

    @TempDir Path dir;

    private InputStream originalIn;

    @BeforeEach
    void closeTheConsole() {
        originalIn = System.in;
        System.setIn(new ByteArrayInputStream(new byte[0]));
    }

    @AfterEach
    void restore() {
        System.setIn(originalIn);
        ConsoleInput.reset();
    }

    @Test
    void theConsoleSaysNobodyCanAnswerAndKeepsTheQuestion() {
        assertThatThrownBy(() -> new ConsoleHumanInterface().promptHuman("Approve? (yes/no)"))
                .isInstanceOf(NoHumanAvailable.class)
                .satisfies(
                        e ->
                                assertThat(((NoHumanAvailable) e).question())
                                        .isEqualTo("Approve? (yes/no)"));
    }

    @Test
    void aSteeredConsoleThatClosesWhileAQuestionIsOpenStopsWaiting() {
        ConsoleInput.start(
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()));
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () ->
                        assertThatThrownBy(() -> ConsoleInput.answer("Approve?"))
                                .isInstanceOf(NoHumanAvailable.class));
    }

    @Test
    void aRunThatNeedsAPersonOnAClosedConsoleEndsWithTheWaysOut() throws Exception {
        Path script = dir.resolve("ask.loom");
        Files.writeString(
                script,
                """
                workflow Main() {
                    human_prompt "Approve sending the refund? (yes/no)" -> approval
                    return approval
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code =
                WeaveCLI.execute(
                        script.toFile(),
                        null,
                        "Main",
                        Map.of(),
                        model -> null,
                        null,
                        null,
                        null,
                        null,
                        new ConsoleHumanInterface(),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8));
        String said = err.toString(StandardCharsets.UTF_8);
        assertThat(code).isEqualTo(1);
        assertThat(said)
                .contains("nobody can answer")
                .contains("Approve sending the refund? (yes/no)")
                .contains("--ask-via")
                .contains("weave answer")
                .doesNotContain("NoSuchElementException")
                .doesNotContain("\tat ");
    }
}
