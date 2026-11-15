package io.github.llm4j.loom.effects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.security.AuditReport;
import io.github.llm4j.loom.security.SecurityAudit;
import io.github.llm4j.mcp.testkit.FakeMcpServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The guide's statements about default and declared effects, checked through the production path
 * (phase 36a, D13 and G78): each script is loaded and the audit is asked which tools of an agent
 * need approval (the ones whose effect is {@code changes}), never a helper. The phrases are pinned,
 * in the style of {@code InvokerDocsTest}, so editing the guide forces this test to change.
 */
class EffectDefaultsDocsTest {

    static final String GUIDE = read();

    static String read() {
        try {
            return Files.readString(Path.of("LOOM_GUIDE.md"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The names of the tools of the only agent that the audit says need approval. */
    private static List<String> needApproval(String declarations, String agentTools) {
        String source =
                declarations
                        + "\nagent A { model: \"m\" system: \"s\" "
                        + agentTools
                        + " }\nworkflow Main() { delegate \"go\" to A -> r }\n";
        AuditReport report =
                SecurityAudit.audit(EffectsFixture.parse(source), "t.loom");
        return new ArrayList<>(report.agents().get(0).unapprovedEffects());
    }

    // ── the phrases ──────────────────────────────────────────────────────────────────────────

    @Test
    void theGuideSaysWhatItSaysAboutDefaults() {
        assertThat(GUIDE)
                .contains("an `http` tool `reads` when it allows only `GET` and `changes` otherwise")
                .contains("an `openapi` operation, a `class` tool, a tool the host registered and an unknown kind `changes`")
                .contains("A tool of an MCP server changes unless the `mcp` block's `effects:` says otherwise")
                .contains("the server's own annotations (`readOnlyHint` and the rest) are untrusted, so they are never used as an effect on their own")
                .contains("You decide, and your word is final for the runtime, `weave check`, `weave audit` and `weave review`")
                .contains("An operation not listed falls back to the tool's own `effect:`, and without one to what its HTTP method says (a `GET` reads)")
                .doesNotContain("the server's own annotations are used when they have been read");
    }

    // ── each statement, asked of the audit ───────────────────────────────────────────────────

    @Test
    void anHttpToolReadsWhenItAllowsOnlyGetAndChangesOtherwise() {
        String tools =
                "tool Look { use: http base_url: \"https://a.example\" }\n"
                        + "tool Post { use: http base_url: \"https://a.example\" methods: \"GET, POST\" }";

        assertThat(needApproval(tools, "tools: [Look, Post]")).containsExactly("Post");
    }

    @Test
    void anOpenapiOperationChangesUnlessYouDeclareOtherwise() {
        String tools =
                "tool Undeclared { use: openapi spec: \"s.json\" operation: \"getPet\" }\n"
                        + "tool Declared { use: openapi spec: \"s.json\" operation: \"getPet\" effect: reads }";

        assertThat(needApproval(tools, "tools: [Undeclared, Declared]")).containsExactly("Undeclared");
    }

    @Test
    void aDeclaredEffectIsFinalWhateverTheKindWouldAssume() {
        String tools =
                "tool Mail { use: webhook url: \"https://hooks.example/x\" effect: reads }\n"
                        + "tool Send { use: webhook url: \"https://hooks.example/y\" }";

        assertThat(needApproval(tools, "tools: [Mail, Send]")).containsExactly("Send");
    }

    @Test
    void anMcpToolIsAssumedToChangeAndOnlyAnEffectsEntryChangesThat() {
        List<String> command = FakeMcpServer.command();
        List<String> quoted = new ArrayList<>();
        for (String a : command.subList(1, command.size())) quoted.add("\"" + a + "\"");
        String block =
                "mcp Fake { cmd: \"" + command.get(0) + "\" args: [" + String.join(", ", quoted)
                        + "] isolation: process effects: { echo: reads, drop: changes } }";

        List<String> unapproved = needApproval(block, "mcp_servers: [Fake]");

        assertThat(unapproved).containsExactly("drop");
    }

    @Test
    void theOlderOpenapiToolFallsBackToItsOwnEffectForAnOperationNotListed() {
        String tools =
                "tool Shop { use: openapi spec: \"s.json\" effect: reads effects: { createRefund: changes } }";

        // the audit sees the tool whole: a declared effect of reads, so it needs no approval
        assertThat(needApproval(tools, "tools: [Shop]")).isEmpty();
    }
}
