package io.github.llm4j.loom.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 36a, D14 and G95: LA31 for an MCP server that runs without a sandbox, and LA34 for an
 * {@code openapi} tool that exposes a whole API, fire at the right severity, and not otherwise.
 */
class IntegrationAuditTest {

    static List<Finding> rule(String rule, String declarations) {
        LoomScript script =
                new LoomParser(new Lexer(declarations + "\nworkflow Main() { note \"x\" }").tokenize())
                        .parseScript();
        return SecurityAudit.audit(script, "t.loom").findings().stream()
                .filter(f -> f.rule().equals(rule))
                .toList();
    }

    // ── LA31 for mcp blocks ──────────────────────────────────────────────────────────────────

    @Test
    void anMcpServerWithNoIsolationLineIsAProgramRunWithoutASandbox() {
        var found = rule("LA31", "mcp Files { transport: \"stdio\" cmd: \"npx -y files-server\" }");

        assertThat(found)
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
                            assertThat(f.where()).isEqualTo("mcp Files");
                            assertThat(f.risk())
                                    .contains("npx -y files-server")
                                    .contains("no isolation:")
                                    .contains("whole environment");
                            assertThat(f.owasp()).contains(Owasp.LLM03, Owasp.LLM06);
                        });
    }

    @Test
    void processAndNoneIsolationAreStillNotASandbox() {
        assertThat(rule("LA31", "mcp F { cmd: \"srv\" isolation: process }"))
                .singleElement()
                .satisfies(f -> assertThat(f.risk()).contains("isolation: process"));
        assertThat(rule("LA31", "mcp F { cmd: \"srv\" isolation: none }"))
                .singleElement()
                .satisfies(f -> assertThat(f.risk()).contains("isolation: none"));
    }

    @Test
    void aServerGivenSecretsThroughEnvIsHighAndTheFindingNamesThemNeverTheirValues() {
        var found =
                rule(
                        "LA31",
                        "mcp F { cmd: \"srv\" isolation: process env: { API_TOKEN: secret.FILES, MODE: \"read\" } }");

        assertThat(found)
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.severity()).isEqualTo(Severity.HIGH);
                            assertThat(f.risk()).contains("API_TOKEN from secret.FILES");
                            assertThat(f.risk()).doesNotContain("read\"").doesNotContain("MODE");
                        });
    }

    @Test
    void aServerWithNoSecretsAndAnIsolationLineIsMedium() {
        assertThat(rule("LA31", "mcp F { cmd: \"srv\" isolation: process env: { MODE: \"read\" } }"))
                .singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.MEDIUM));
    }

    // ── LA34 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void anOpenapiToolWithNoOperationExposesTheWholeApi() {
        var found = rule("LA34", "tool Pets { use: openapi spec: \"specs/petstore.json\" }");

        assertThat(found)
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
                            assertThat(f.where()).isEqualTo("tool Pets");
                            assertThat(f.risk()).contains("every operation").contains("specs/petstore.json");
                            assertThat(f.fix()).contains("operation:").contains("weave connect");
                        });
    }

    @Test
    void anOpenapiToolThatNamesItsOperationRaisesNoFinding() {
        assertThat(
                        rule(
                                "LA34",
                                "tool Pet { use: openapi spec: \"p.json\" operation: \"getPetById\" effect: reads }"))
                .isEmpty();
    }

    @Test
    void otherKindsRaiseNoLa34() {
        assertThat(rule("LA34", "tool H { use: http base_url: \"https://a.example\" }")).isEmpty();
    }
}
