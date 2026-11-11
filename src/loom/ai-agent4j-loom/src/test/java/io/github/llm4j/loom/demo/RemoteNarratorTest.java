package io.github.llm4j.loom.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.AskStmt;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The narrator describes a remote step and an ask in plain words, and says a remote is outside the budgets and not trusted. */
class RemoteNarratorTest {

    private static io.github.llm4j.loom.ast.Statement statement(String body) {
        return new LoomParser(new Lexer("agent_channel Desk { audience: staff }\nremote Supplier { card: \"https://s.example.com/c.json\" agent_channel: Desk }\nworkflow W() { " + body + " }").tokenize())
                .parseScript().getWorkflows().get(0).getStatements().get(0);
    }

    @Test
    void theNarratorSaysARemoteIsRunBySomeoneElseOutsideTheBudgetsAndNotTrusted() {
        var line = Narrator.line("W/s0", statement("delegate \"q\" to Supplier -> quote"), new RunJournal.Entry("remote", Map.of("text", "42 each")), null);
        assertThat(line.label()).isEqualTo("remote Supplier");
        assertThat(line.text()).contains("someone else runs").contains("42 each").contains("outside your budgets").contains("not trusted");
        var failed = Narrator.line("W/s0", statement("delegate \"q\" to Supplier -> quote"), new RunJournal.Entry("remote_failed", "no"), null);
        assertThat(failed.text()).contains("did not give an answer");
    }

    @Test
    void theNarratorDescribesAnAsk() {
        var ask = statement("ask Desk \"Which?\" -> r");
        assertThat(ask).isInstanceOf(AskStmt.class);
        assertThat(Narrator.line("W/s0", ask, new RunJournal.Entry("ask", "USD"), null).text()).contains("channel Desk").contains("USD");
        assertThat(Narrator.line("W/s0", ask, new RunJournal.Entry("ask_failed", "timed out"), null).text()).contains("did not answer");
    }
}
