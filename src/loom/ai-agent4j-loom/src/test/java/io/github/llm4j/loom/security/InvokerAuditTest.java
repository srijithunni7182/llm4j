package io.github.llm4j.loom.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@code weave audit} on a tool that acts as the person running the workflow. */
class InvokerAuditTest {

    private static final String TOOL =
            "tool Refunds { use: openapi  spec: \"refunds.json\"  auth_header: \"Authorization\"  auth_value: invoker.token }\n";

    private static List<Finding> la17(String source) {
        var script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        return SecurityAudit.audit(script, "w.loom").findings().stream()
                .filter(f -> f.rule().equals("LA17"))
                .toList();
    }

    @Test
    @Tag("CC-V4.5")
    void anAgentThatMayCallItWithoutApprovalIsAMediumFindingNamingIt() {
        List<Finding> found =
                la17(
                        TOOL
                                + "agent Scout { model: \"m\" tools: [Refunds] }\nagent Other { model: \"m\" tools: [Refunds] approve: [Refunds] }");

        assertThat(found).hasSize(1);
        Finding f = found.get(0);
        assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(f.title()).isEqualTo("A tool that acts as the person running the workflow");
        assertThat(f.risk())
                .contains("invoker.token")
                .contains("that person's permissions")
                .contains("Scout may call it without approval")
                .doesNotContain("Other");
        assertThat(f.fix()).contains("approve: [Refunds] to Scout");
        assertThat(f.owasp()).contains(Owasp.LLM06);
    }

    @Test
    @Tag("CC-V4.5")
    void whenEveryAgentApprovesEachCallItIsOnlyLow() {
        for (String agent :
                List.of(
                        "agent Scout { model: \"m\" tools: [Refunds] approve: [Refunds] }",
                        "agent Scout { model: \"m\" tools: [Refunds] approve: all }")) {
            List<Finding> found = la17(TOOL + agent);

            assertThat(found).as(agent).hasSize(1);
            assertThat(found.get(0).severity()).isEqualTo(Severity.LOW);
            assertThat(found.get(0).risk()).contains("must have each call approved");
            assertThat(found.get(0).fix())
                    .isEqualTo("Give the downstream API the narrowest scopes for the token.");
        }
    }

    @Test
    @Tag("CC-V4.6")
    void aToolNoAgentUsesIsNotAFinding() {
        assertThat(la17(TOOL + "agent Scout { model: \"m\" }")).isEmpty();
    }

    @Test
    @Tag("CC-V4.5")
    void otherCredentialsAreNotThisFinding() {
        assertThat(
                        la17(
                                "tool Refunds { use: openapi  spec: \"refunds.json\"  auth_header: \"Authorization\"  auth_value: env.TOKEN }\n"
                                        + "agent Scout { model: \"m\" tools: [Refunds] }"))
                .isEmpty();
    }

    @Test
    @Tag("CC-V5.6")
    void theFindingNamesTheCredentialAndNeverAValue() {
        var script =
                new LoomParser(
                                new Lexer(TOOL + "agent Scout { model: \"m\" tools: [Refunds] }")
                                        .tokenize())
                        .parseScript();

        String text =
                SecurityAudit.audit(script, "w.loom").findings().stream()
                        .filter(f -> f.rule().equals("LA17"))
                        .map(f -> f.where() + f.title() + f.risk() + f.fix())
                        .findFirst()
                        .orElseThrow();

        assertThat(text).contains("invoker.token").doesNotContain("Bearer");
    }
}
