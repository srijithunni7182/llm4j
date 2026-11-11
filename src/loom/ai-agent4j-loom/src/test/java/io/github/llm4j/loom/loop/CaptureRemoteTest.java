package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A run with a remote step cannot be turned into a case yet; capture says so and writes nothing wrong. */
class CaptureRemoteTest {

    @TempDir Path dir;

    @Test
    void aRunThatCalledARemoteAgentIsRefusedWithWhatToDoInstead() throws Exception {
        Path s = Runs.project(dir.resolve("proj"), """
                agent_channel Desk { audience: staff }
                remote Supplier { card: "https://supplier.example.com/card.json" agent_channel: Desk }
                workflow Main(part) {
                    delegate "Quote {part}" to Supplier -> quote
                    return quote
                }
                """);
        Path r = Runs.run(dir.resolve("store"), "r1", s, """
                input: { part: bolt }
                remotes:
                  Supplier:
                    - { state: completed, text: "42 each" }
                """, Map.of("part", "bolt"));
        RunRecord rec = StoreReader.open(r);
        assertThatThrownBy(() -> Capture.capture(rec, Capture.Options.defaults(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remote agent")
                .hasMessageContaining("remotes:");
        assertThat(dir.resolve("proj").resolve("tests")).doesNotExist();
    }
}
