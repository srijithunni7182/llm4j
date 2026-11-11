package io.github.llm4j.loom.symbols;

import static io.github.llm4j.loom.symbols.SymbolsFixture.binding;
import static io.github.llm4j.loom.symbols.SymbolsFixture.def;
import static io.github.llm4j.loom.symbols.SymbolsFixture.index;
import static io.github.llm4j.loom.symbols.SymbolsFixture.refs;
import static io.github.llm4j.loom.symbols.SymbolsFixture.slice;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** docs/specs/32-a2a-integration R32: remote, agent_channel and expose are definitions with references, so go-to-definition, find references and rename work. */
class SymbolsA2ATest {

    @TempDir Path dir;

    private static final String SCRIPT = """
            agent_channel Desk { audience: staff }
            remote Supplier {
                card: "https://supplier.example.com/card.json"
                agent_channel: Desk
            }
            expose Expense {
                workflow: Check
                description: "Checks"
                agent_channel: Desk
            }
            workflow Buy(part) {
                delegate "Quote {part}" to Supplier -> quote expecting { price: number }
                ask Desk "Which currency?" -> currency
                note "{quote.price} {currency}"
            }
            workflow Check(receipt) { note "x" }
            """;

    @Test
    void theThreeDeclarationsAreDefinitionsOfTheirOwnKinds() throws Exception {
        var i = index(dir, "main.loom", SCRIPT);
        assertThat(slice(SCRIPT, def(i, "remote:Supplier").range())).isEqualTo("Supplier");
        assertThat(slice(SCRIPT, def(i, "channel:Desk").range())).isEqualTo("Desk");
        assertThat(slice(SCRIPT, def(i, "expose:Expense").range())).isEqualTo("Expense");
        assertThat(def(i, "remote:Supplier").extent()).isNotNull();
    }

    @Test
    void aDelegateTargetAnAskAndAnAgentChannelSettingResolveToTheirDefinitions() throws Exception {
        var i = index(dir, "main.loom", SCRIPT);
        assertThat(refs(i, "remote:Supplier")).hasSize(1).allMatch(r -> r.resolved() && r.role().equals("delegate-target"));
        var channelRefs = refs(i, "channel:Desk");
        assertThat(channelRefs).extracting(r -> r.role()).containsExactlyInAnyOrder("remote-channel", "expose-channel", "ask");
        assertThat(channelRefs).allMatch(r -> r.resolved());
        assertThat(refs(i, "workflow:Check")).anyMatch(r -> r.role().equals("expose-workflow") && r.resolved());
    }

    @Test
    void everyReferenceRangeCoversExactlyTheName() throws Exception {
        var i = index(dir, "main.loom", SCRIPT);
        for (var r : refs(i, "channel:Desk")) assertThat(slice(SCRIPT, r.range())).isEqualTo("Desk");
        for (var r : refs(i, "remote:Supplier")) assertThat(slice(SCRIPT, r.range())).isEqualTo("Supplier");
    }

    @Test
    void anAskBindsItsAnswerAndAtypedAnswerAndARemoteResultHaveTheirTypes() throws Exception {
        var i = index(dir, "main.loom", SCRIPT);
        assertThat(binding(i, "binding:Buy/currency").type().toString()).containsIgnoringCase("string");
        assertThat(binding(i, "binding:Buy/quote").type().toString()).contains("price");
        assertThat(binding(i, "binding:Buy/currency").producedBy()).isEqualTo("ask");
    }

    @Test
    void aNameThatIsNotDeclaredIsAnUnresolvedReference() throws Exception {
        String text = "workflow W() { ask Nobody \"q\" -> r\n note \"{r}\" }";
        var i = index(dir, "other.loom", text);
        assertThat(refs(i, "channel:Nobody")).hasSize(1).noneMatch(r -> r.resolved());
    }

    @Test
    void aHalfTypedDeclarationStillGivesWhatItHas() throws Exception {
        var i = index(dir, "half.loom", "remote Supplier {\n card: \"https://x.example.com/c.json\"\n agent_channel: Desk\n");
        assertThat(i.definitions()).anyMatch(d -> d.key().equals("remote:Supplier"));
    }
}
