package io.github.llm4j.loom.debug;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 32c: the stepper stops before a call to a remote agent like before any other statement, and
 * names it. It has no A2A transport, so it never sends anything: stepping over the call is refused
 * with a message that says so.
 */
class StepperRemoteTest {

    private static final String SCRIPT = """
            agent_channel Desk { audience: staff }
            remote Supplier {
                card: "https://agent.example.test/.well-known/agent-card.json"
                agent_channel: Desk
            }
            workflow main(part) {
                delegate "Quote {part}" to Supplier -> quote
                return quote
            }
            """;

    @TempDir Path dir;

    Stepper stepper;

    @AfterEach
    void close() {
        if (stepper != null) stepper.close();
    }

    @Test
    void pausesBeforeTheCallAndNamesTheRemote() throws Exception {
        Path file = dir.resolve("s.loom");
        Files.writeString(file, SCRIPT);
        stepper = Stepper.open(new Stepper.Config(file, "main", Map.of("part", "12"), Set.of(), RunJournal.inMemory(), null));

        var s = stepper.state();

        assertThat(s.finished()).isFalse();
        assertThat(s.position().kind()).isEqualTo("remote");
        assertThat(s.position().statement()).contains("Supplier");
    }

    @Test
    void steppingOverTheCallWithNoTransportSendsNothingAndSaysWhy() throws Exception {
        Path file = dir.resolve("s.loom");
        Files.writeString(file, SCRIPT);
        stepper = Stepper.open(new Stepper.Config(file, "main", Map.of("part", "12"), Set.of(), RunJournal.inMemory(), null));

        var s = stepper.move(Stepper.Move.OVER);

        assertThat(s.finished()).isTrue();
        assertThat(s.status()).isEqualTo("failed");
        assertThat(String.valueOf(s.reason())).containsIgnoringCase("A2A");
    }
}
