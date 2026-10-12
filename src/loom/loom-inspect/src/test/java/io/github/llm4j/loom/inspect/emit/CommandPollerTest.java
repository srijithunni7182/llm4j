package io.github.llm4j.loom.inspect.emit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.wire.Command;
import io.github.llm4j.loom.inspect.wire.CommandResult;
import io.github.llm4j.loom.inspect.wire.Wire;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a process does with the commands the server hands it. */
class CommandPollerTest {

    private final List<String> posted = new ArrayList<>();
    private final List<Command> handled = new ArrayList<>();

    private final Transport transport = (path, body) -> {
        posted.add(path + " " + body);
        return new Transport.Reply(200, "{}", 0);
    };

    private CommandPoller poller() {
        return new CommandPoller("alpha", transport, c -> {
            handled.add(c);
            return new CommandResult(c.id(), true, "done");
        }, Clock.systemUTC(), new PrintStream(new java.io.ByteArrayOutputStream()), 0, 10);
    }

    private static String body(String client, Command... cs) {
        return Wire.write(java.util.Map.of("client", client, "commands", List.of(cs)));
    }

    private static Command fresh(String id) {
        return new Command(id, "t".repeat(32), "stop", "ops", Instant.now().toString(), Instant.now().plusSeconds(60).toString());
    }

    @Test
    void aFreshCommandIsCarriedOutOnceAndItsResultIsReported() {
        CommandPoller p = poller();
        p.handle(body("alpha", fresh("c1")));
        p.handle(body("alpha", fresh("c1"))); // the same answer replayed
        assertThat(handled).hasSize(1);
        assertThat(posted).anyMatch(m -> m.startsWith(CommandPoller.RESULT) && m.contains("\"c1\"") && m.contains("\"accepted\":true"));
        assertThat(posted).anyMatch(m -> m.contains("already handled"));
    }

    @Test
    void anExpiredCommandOrOneForAnotherProcessIsNotCarriedOut() {
        CommandPoller p = poller();
        p.handle(body("alpha", new Command("c2", "t", "stop", "ops", "x", Instant.now().minusSeconds(5).toString())));
        p.handle(body("beta", fresh("c3")));
        assertThat(handled).isEmpty();
        assertThat(posted).anyMatch(m -> m.contains("expired"));
    }

    @Test
    void aFailingHandlerIsReportedNotThrown() throws IOException {
        CommandPoller p = new CommandPoller("alpha", transport, c -> { throw new IllegalStateException("boom"); }, Clock.systemUTC(), new PrintStream(new java.io.ByteArrayOutputStream()), 0, 10);
        p.handle(body("alpha", fresh("c4")));
        assertThat(posted).anyMatch(m -> m.contains("\"accepted\":false") && m.contains("boom"));
    }
}
