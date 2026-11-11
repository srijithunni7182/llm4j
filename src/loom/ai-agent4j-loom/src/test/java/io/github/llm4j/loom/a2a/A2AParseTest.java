package io.github.llm4j.loom.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.AskStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import org.junit.jupiter.api.Test;

/** The three A2A declarations and the ask statement parse, with every setting, and stay contextual words. */
class A2AParseTest {

    static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private static final String FULL = """
            agent_channel SupplierDesk {
                audience: staff
                max_questions: 3
                timeout: 1h
            }

            remote Supplier {
                card: "https://supplier.example.com/.well-known/agent-card.json"
                auth: secret.SUPPLIER_KEY
                agent_channel: SupplierDesk
                timeout: 2h
                require_signed_card: true
            }

            expose Expense {
                workflow: Expense
                description: "Reads a receipt and returns the total and currency."
                agent_channel: SupplierDesk
                text_input: receipt
                max_input: 32k
                skills: [ { id: "read_receipt" name: "Read a receipt" } ]
                budget { tokens: 20000 calls: 10 }
                timeout: 30m
            }

            workflow Buy(part) {
                delegate "Quote 500 units of {part}" to Supplier -> quote
                    retry 2
                    on_failure { halt "the supplier could not quote" }
                ask SupplierDesk "Which currency?" -> reply expecting { currency: enum["USD", "INR"] }
            }

            workflow Expense(receipt) {
                note "x"
            }
            """;

    @Test
    void theThreeDeclarationsAndTheAskStatementParseWithEverySetting() {
        LoomScript script = parse(FULL);

        var channel = script.getAgentChannels().get(0);
        assertThat(channel.getName()).isEqualTo("SupplierDesk");
        assertThat(channel.get("audience")).isEqualTo("staff");
        assertThat(channel.getInt("max_questions", 0)).isEqualTo(3);
        assertThat(channel.get("timeout")).isEqualTo("1h");

        var remote = script.getRemotes().get(0);
        assertThat(remote.getName()).isEqualTo("Supplier");
        assertThat(remote.get("card")).startsWith("https://supplier.example.com");
        assertThat(remote.getValues().get("auth").source()).isEqualTo(io.github.llm4j.loom.ast.ToolDef.OptionValue.Source.SECRET);
        assertThat(remote.get("agent_channel")).isEqualTo("SupplierDesk");
        assertThat(remote.get("require_signed_card")).isEqualTo("true");

        var expose = script.getExposes().get(0);
        assertThat(expose.getName()).isEqualTo("Expense");
        assertThat(expose.get("workflow")).isEqualTo("Expense");
        assertThat(expose.get("text_input")).isEqualTo("receipt");
        assertThat(expose.get("max_input")).isEqualTo("32k");
        assertThat(expose.getSkills()).hasSize(1);
        assertThat(expose.getSkills().get(0).id()).isEqualTo("read_receipt");
        assertThat(expose.getSkills().get(0).name()).isEqualTo("Read a receipt");
        assertThat(expose.getBudget()).isNotNull();

        var delegate = (DelegateStmt) script.getWorkflows().get(0).getStatements().get(0);
        assertThat(delegate.getTargetAgent()).isEqualTo("Supplier");
        var ask = (AskStmt) script.getWorkflows().get(0).getStatements().get(1);
        assertThat(ask.getChannel()).isEqualTo("SupplierDesk");
        assertThat(ask.getText()).isEqualTo("Which currency?");
        assertThat(ask.getVariable()).isEqualTo("reply");
        assertThat(ask.getExpecting()).isNotNull();
        assertThat(ask.getLine()).isGreaterThan(0);
    }

    @Test
    void anAskCarriesRetryTimeoutAndOnFailureLikeAnyStep() {
        var ask = (AskStmt) parse("""
                workflow W() {
                    ask Desk "Which?" -> r retry 2 backoff 1s timeout 30s
                        on_failure { halt "no answer" }
                }
                """).getWorkflows().get(0).getStatements().get(0);
        assertThat(ask.getRetryCount()).isEqualTo(2);
        assertThat(ask.getBackoffMillis()).isEqualTo(1000);
        assertThat(ask.getTimeoutMillis()).isEqualTo(30_000);
        assertThat(ask.getOnFailure()).hasSize(1);
        assertThat(ask.getExpecting()).isNull();
    }

    @Test
    void theFourWordsAreContextualSoExistingNamesStillParse() {
        LoomScript script = parse("""
                agent remote { model: "m" system: "s" }
                agent expose { model: "m" system: "s" }
                agent ask { model: "m" system: "s" }
                agent agent_channel { model: "m" system: "s" }
                workflow W(remote, expose, ask) {
                    delegate "hi {ask}" to remote -> expose
                }
                """);
        assertThat(script.getAgents()).hasSize(4);
        assertThat(script.getRemotes()).isEmpty();
        assertThat(script.getExposes()).isEmpty();
        assertThat(script.getAgentChannels()).isEmpty();
    }

    @Test
    void mistakesSayWhatToWrite() {
        assertThatThrownBy(() -> parse("remote S { card: \"https://x.example.com\" card: \"https://y.example.com\" }"))
                .hasMessageContaining("card is given twice");
        assertThatThrownBy(() -> parse("workflow W() { ask Desk -> r }"))
                .hasMessageContaining("ask");
        assertThatThrownBy(() -> parse("workflow W() { ask Desk \"q\" }"))
                .hasMessageContaining("->");
        assertThatThrownBy(() -> parse("expose E { skills: [ { name: \"x\" } ] }"))
                .hasMessageContaining("id");
        assertThatThrownBy(() -> parse("agent_channel C { audience: staff }\nagent_channel C { audience: staff }"))
                .hasMessageContaining("C");
    }

    @Test
    void aScriptWithNoneOfTheNewWordsIsUnchanged() {
        LoomScript script = parse("agent A { model: \"m\" system: \"s\" }\nworkflow W() { delegate \"hi\" to A -> r }");
        assertThat(script.getRemotes()).isEmpty();
        assertThat(script.getWorkflows().get(0).getStatements()).hasSize(1);
    }
}
