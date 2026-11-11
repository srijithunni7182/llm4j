package io.github.llm4j.loom.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * docs/specs/32-a2a-integration R27 (LA24 to LA27): what a script that calls remote agents, or lets
 * others call it, hands out and takes in.
 */
class ExposedAuditTest {

    private static List<Finding> audit(String source) {
        var script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        return SecurityAudit.audit(script, "w.loom").findings();
    }

    private static List<Finding> rule(List<Finding> all, String id) {
        return all.stream().filter(f -> f.rule().equals(id)).toList();
    }

    private static final String HEAD = """
            agent Clerk { model: "gemini-2.5-flash" system: "x" }
            agent_channel Desk { audience: caller }
            budget { tokens: 100000 calls: 50 }
            """;

    private static final String EXPOSED = HEAD + """
            expose Totals { workflow: Total description: "Totals." agent_channel: Desk text_input: receipt }
            workflow Total(receipt) {
                delegate "Total {receipt}" to Clerk -> out
                return out
            }
            """;

    // ---- LA24

    @Test
    void anExposedWorkflowIsReportedAndItsAgentsCountAsReadingUntrustedContent() {
        List<Finding> all = audit(EXPOSED);

        assertThat(rule(all, "LA24")).singleElement().satisfies(f -> {
            assertThat(f.where()).isEqualTo("Totals");
            assertThat(f.severity()).isEqualTo(Severity.LOW);
            assertThat(f.owasp()).contains(Owasp.LLM01);
            assertThat(f.risk()).contains("Clerk");
        });
        assertThat(SecurityAudit.audit(new LoomParser(new Lexer(EXPOSED).tokenize()).parseScript(), "w.loom").agents())
                .filteredOn(a -> a.agent().equals("Clerk"))
                .singleElement()
                .satisfies(a -> assertThat(a.untrusted()).anyMatch(u -> u.contains("exposed")));
    }

    @Test
    void anAgentTheExposedWorkflowDoesNotReachIsNotCountedAsExposed() {
        String source = EXPOSED + "agent Other { model: \"gemini-2.5-flash\" system: \"x\" }\n";

        var report = SecurityAudit.audit(new LoomParser(new Lexer(source).tokenize()).parseScript(), "w.loom");

        assertThat(report.agents()).filteredOn(a -> a.agent().equals("Other")).singleElement().satisfies(a -> assertThat(a.untrusted()).isEmpty());
    }

    @Test
    void whatAnExposedWorkflowCallsInTurnIsReachedToo() {
        String source = HEAD
                + "agent Deep { model: \"gemini-2.5-flash\" system: \"x\" }\n"
                + "expose Totals { workflow: Total description: \"Totals.\" agent_channel: Desk text_input: receipt }\n"
                + "workflow Total(receipt) { call Inner(receipt = receipt) -> out\n return out }\n"
                + "workflow Inner(receipt) { delegate \"x {receipt}\" to Deep -> out\n return out }\n";

        var report = SecurityAudit.audit(new LoomParser(new Lexer(source).tokenize()).parseScript(), "w.loom");

        assertThat(report.agents()).filteredOn(a -> a.agent().equals("Deep")).singleElement().satisfies(a -> assertThat(a.untrusted()).isNotEmpty());
    }

    @Test
    void noExposeMeansNoLa24() {
        assertThat(rule(audit(HEAD + "workflow M() { delegate \"x\" to Clerk -> out }"), "LA24")).isEmpty();
    }

    // ---- LA25

    private static final String REMOTE = """
            remote Supplier {
                card: "https://supplier.example.com/.well-known/agent-card.json"
                agent_channel: Desk
            }
            """;

    @Test
    void sendingToARemoteAgentIsReportedByNameAndSaysNothingMasksPersonalData() {
        List<Finding> found = rule(audit(HEAD + REMOTE + "workflow Buy(part) { delegate \"Quote {part}\" to Supplier -> quote }"), "LA25");

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.where()).contains("Supplier");
            assertThat(f.severity()).isEqualTo(Severity.LOW);
            assertThat(f.owasp()).contains(Owasp.LLM02);
            assertThat(f.risk()).containsIgnoringCase("masks");
        });
    }

    @Test
    void itIsMediumWhenTheWorkflowReachesPrivateDataAndNothingMasksIt() {
        String source = HEAD.replace("agent Clerk { model: \"gemini-2.5-flash\" system: \"x\" }", "tool Files { use: file root: \".\" mode: read }\nagent Clerk { model: \"gemini-2.5-flash\" system: \"x\" tools: [Files] }")
                + REMOTE
                + "workflow Buy(part) { delegate \"look {part}\" to Clerk -> a\n delegate \"Quote {a}\" to Supplier -> quote }\n";

        assertThat(rule(audit(source), "LA25")).singleElement().satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.MEDIUM));
    }

    @Test
    void aGuardThatMasksPersonalDataKeepsItLow() {
        String source = HEAD.replace("agent Clerk { model: \"gemini-2.5-flash\" system: \"x\" }", "tool Files { use: file root: \".\" mode: read }\nagent Clerk { model: \"gemini-2.5-flash\" system: \"x\" tools: [Files] guard { pii: mask } }")
                + REMOTE
                + "workflow Buy(part) { delegate \"look {part}\" to Clerk -> a\n delegate \"Quote {a}\" to Supplier -> quote }\n";

        assertThat(rule(audit(source), "LA25")).singleElement().satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.LOW));
    }

    @Test
    void aLocalDelegateIsNotLa25() {
        assertThat(rule(audit(HEAD + "workflow M() { delegate \"x\" to Clerk -> out }"), "LA25")).isEmpty();
    }

    // ---- LA26

    @Test
    void anAutomaticChannelIsMediumAndHighWhenARemoteCanAskItForAuthorization() {
        String automatic = "agent_channel Auto { audience: automatic }\n";

        List<Finding> plain = rule(audit(automatic + "workflow M() { ask Auto \"ok?\" -> r }"), "LA26");
        List<Finding> withRemote = rule(audit(automatic + "remote Supplier { card: \"https://s.example.com/card.json\" agent_channel: Auto }\nworkflow M() { delegate \"q\" to Supplier -> r }"), "LA26");

        assertThat(plain).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(f.owasp()).contains(Owasp.LLM06);
        });
        assertThat(withRemote).singleElement().satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.HIGH));
    }

    @Test
    void staffAndCallerChannelsAreNotLa26() {
        assertThat(rule(audit(HEAD + "agent_channel Staff { audience: staff }\nworkflow M() { ask Staff \"ok?\" -> r }"), "LA26")).isEmpty();
    }

    // ---- LA27

    @Test
    void anExposedWorkflowWithNoSpendCapIsHigh() {
        String noBudget = EXPOSED.replace("budget { tokens: 100000 calls: 50 }\n", "");

        assertThat(rule(audit(noBudget), "LA27")).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.HIGH);
            assertThat(f.owasp()).contains(Owasp.LLM10);
            assertThat(f.where()).isEqualTo("Totals");
        });
    }

    @Test
    void aScriptBudgetOrAnExposeBudgetIsACap() {
        assertThat(rule(audit(EXPOSED), "LA27")).as("a script budget").isEmpty();

        String exposeBudget = EXPOSED.replace("budget { tokens: 100000 calls: 50 }\n", "")
                .replace("text_input: receipt }", "text_input: receipt budget { tokens: 20000 } }");
        assertThat(rule(audit(exposeBudget), "LA27")).as("an expose budget").isEmpty();
    }
}
