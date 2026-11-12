package io.github.llm4j.loom.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 33, D15: a script that can steer a started workflow is an input channel (LA28). */
class ChildAuditTest {

    private static final String AGENTS =
            """
            tool Crm { use: file  root: "data"  mode: read }
            tool Mail { use: email  host: "smtp.example.com"  from: "a@example.com"  to: "me@example.com" }
            agent Researcher { model: "m" system: "s" steerable: true streaming: true }
            agent Sender { model: "m" system: "s" tools: [Crm, Mail] steerable: true streaming: true }
            agent Quiet { model: "m" system: "s" }
            workflow Research(topic) { delegate "go" to Researcher -> r  return r }
            workflow Send(topic) { delegate "go" to Sender -> r  return r }
            budget { tokens: 1000 }
            """;

    private static List<Finding> la28(String main) {
        LoomScript script = new LoomParser(new Lexer(AGENTS + main).tokenize()).parseScript();
        return SecurityAudit.audit(script, "t.loom").findings().stream()
                .filter(f -> f.rule().equals("LA28"))
                .toList();
    }

    @Test
    void aSteerStatementIsAnInputChannelThatNamesTheAgentItReaches() {
        var found =
                la28(
                        """
                        workflow Main(q) {
                            start Research(topic = q) budget { tokens: 10 } -> work
                            steer work Researcher "Narrow the topic."
                            wait work timeout 1m
                        }
                        """);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).severity()).isEqualTo(Severity.INFO);
        assertThat(found.get(0).risk()).contains("Researcher");
        assertThat(found.get(0).owasp()).contains(Owasp.LLM01);
    }

    @Test
    void aSuperviseRuleThatSteersReachesTheChildsSteerableAgents() {
        var found =
                la28(
                        """
                        workflow Main(q) {
                            start Research(topic = q) budget { tokens: 10 } -> work
                            supervise work { when idle > 3m { steer "Narrow the topic." once } }
                            wait work timeout 1m
                        }
                        """);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).risk()).contains("Researcher");
    }

    @Test
    void steeringAnAgentThatReachesPrivateDataAndCanActIsMedium() {
        var found =
                la28(
                        """
                        workflow Main(q) {
                            start Send(topic = q) budget { tokens: 10 } -> work
                            steer work Sender "Send it now."
                            wait work timeout 1m
                        }
                        """);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).severity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void cancellingIsNotAnInputChannelAndGetsNoRule() {
        var found =
                la28(
                        """
                        workflow Main(q) {
                            start Research(topic = q) budget { tokens: 10 } -> work
                            supervise work { when tokens > 5 { cancel use partial } }
                            cancel work reason "x"
                            wait work timeout 1m
                        }
                        """);

        assertThat(found).isEmpty();
    }

    @Test
    void aScriptThatStartsNothingHasNoFinding() {
        assertThat(la28("workflow Main(q) { note \"x\" }")).isEmpty();
    }

    @Test
    void theRuleIsInTheCatalogue() {
        assertThat(io.github.llm4j.loom.review.adapt.AuditCatalog.rules())
                .anyMatch(r -> r.id().equals("LA28"));
    }

    private static List<Finding> la29(String main) {
        LoomScript script = new LoomParser(new Lexer(AGENTS + "evaluator J { model: \"m\" }\n" + main).tokenize()).parseScript();
        return SecurityAudit.audit(script, "t.loom").findings().stream()
                .filter(f -> f.rule().equals("LA29"))
                .toList();
    }

    @Test
    void aJudgedAssertionIsListedWithWhatTheJudgeSeesAndWhatItCanCause() {
        var found =
                la29(
                        """
                        workflow Main(q) {
                            start Research(topic = q) budget { tokens: 10 } -> work
                            supervise work budget { tokens: 100 } {
                                assert "stays on topic" using J every 2m max 3 { steer "Go back." once }
                                assert "reads sources" using J seeing content every 3 steps max 2 { cancel use partial }
                            }
                            wait work timeout 1m
                        }
                        """);

        assertThat(found).hasSize(2);
        var facts = found.stream().filter(f -> f.severity() == Severity.INFO).findFirst().orElseThrow();
        var content = found.stream().filter(f -> f.severity() == Severity.LOW).findFirst().orElseThrow();
        assertThat(facts.risk()).contains("facts").contains("steer");
        assertThat(content.risk()).contains("content").contains("cancel use partial");
    }

    @Test
    void aScriptWithNoJudgedAssertionHasNoLa29() {
        assertThat(la29("workflow Main(q) { note \"x\" }")).isEmpty();
    }
}
