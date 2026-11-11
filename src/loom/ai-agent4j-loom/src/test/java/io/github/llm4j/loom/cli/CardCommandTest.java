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

/** Phase 32d, R25: {@code weave card} prints what an expose block shows outsiders, touching nothing. */
class CardCommandTest {

    private static final String SCRIPT = """
            agent_channel Desk { audience: caller }
            expose Totals {
                workflow: Total
                description: "Totals a receipt."
                agent_channel: Desk
                text_input: receipt
            }
            workflow Total(receipt) { return "ok" }
            """;

    @TempDir Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String source, String address, String expose) throws Exception {
        Path file = dir.resolve("main.loom");
        Files.writeString(file, source);
        WeaveEnv env =
                new WeaveEnv(
                        model -> {
                            throw new IllegalStateException("weave card needs no model");
                        },
                        message -> "",
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"));
        CardCommand command = new CardCommand();
        command.script = file.toFile();
        command.address = address;
        command.expose = expose;
        return CardCommand.card(command, env);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void printsTheCardWithAPlaceholderAddressWhenNoneIsGiven() throws Exception {
        assertThat(run(SCRIPT, null, null)).isZero();

        assertThat(stdout()).contains("\"name\"").contains("Totals").contains("https://your-agent.example.com");
    }

    @Test
    void usesTheAddressItIsGiven() throws Exception {
        assertThat(run(SCRIPT, "https://agent.example.org/a2a", null)).isZero();

        assertThat(stdout()).contains("https://agent.example.org/a2a").doesNotContain("your-agent");
    }

    @Test
    void anAddressThatIsNotHttpsIsAnError() throws Exception {
        assertThat(run(SCRIPT, "http://agent.example.org/a2a", null)).isEqualTo(2);

        assertThat(stderr()).contains("https");
        assertThat(stdout()).isEmpty();
    }

    @Test
    void aScriptWithNoExposeBlockSaysSo() throws Exception {
        assertThat(run("workflow Total(receipt) { return \"ok\" }\n", null, null)).isEqualTo(2);

        assertThat(stderr()).contains("expose");
    }

    @Test
    void anUnknownExposeNameListsTheKnownOnes() throws Exception {
        assertThat(run(SCRIPT, null, "Nope")).isEqualTo(2);

        assertThat(stderr()).contains("Nope").contains("Totals");
    }

    @Test
    void aScriptThatDoesNotLoadIsAnErrorNotACard() throws Exception {
        assertThat(run("expose Totals { workflow: Missing }\n", null, null)).isEqualTo(2);

        assertThat(stdout()).isEmpty();
    }
}
