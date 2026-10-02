package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which agent made a decision, and what does and does not make it a different one (spec loom-earned-autonomy R6.1). */
class AgentIdentityTest {

    @TempDir
    Path dir;

    private static final String BASE = """
            persona Mentor { role: "a mentor" tone: "kind" }
            tool Lookup { use: http  url: env.LOOKUP_URL  api_key: "k-1" }
            agent Triager {
                model: "m-1"
                system: "You are Triager."
                temperature: 0.2
                persona: Mentor
                tools: [Lookup]
                skills: ["fs://policy.md"]
                guard { pii: mask }
                output_schema: { choice: string, reasoning: string }
            }
            agent Other { model: "x" system: "You are Other." }
            decision Refund { proposed by: Triager choices: approve, reject, escalate }
            workflow W() { decide Refund -> v }
            """;

    private String identity(String source) {
        var script = DecisionParseTest.parse(source);
        return AgentIdentity.of(script, script.getDecisions().get(0), dir);
    }

    private String changed(UnaryOperator<String> edit) {
        return identity(edit.apply(BASE));
    }

    @Test
    @Tag("EA-V6.1")
    void commentsWhitespaceReorderingAndOtherAgentsDoNotChangeIt() throws Exception {
        Files.writeString(dir.resolve("policy.md"), "refund within 30 days");
        String base = identity(BASE);
        assertThat(base).hasSize(64);
        assertThat(changed(s -> "// a comment\n" + s.replace("model: \"m-1\"", "model:   \"m-1\"   // trailing"))).isEqualTo(base);
        assertThat(changed(s -> s.replace("agent Other { model: \"x\" system: \"You are Other.\" }", "agent Other { model: \"y\" system: \"changed\" }"))).isEqualTo(base);
        assertThat(changed(s -> s.replace("workflow W() { decide Refund -> v }", "workflow W2() { note \"hello\" }"))).isEqualTo(base);
        String reordered = BASE.replace("tool Lookup { use: http  url: env.LOOKUP_URL  api_key: \"k-1\" }", "").replace("persona Mentor { role: \"a mentor\" tone: \"kind\" }", "")
                + "persona Mentor { role: \"a mentor\" tone: \"kind\" }\ntool Lookup { use: http  api_key: \"k-1\"  url: env.LOOKUP_URL }\n";
        assertThat(identity(reordered)).isEqualTo(base);
    }

    @Test
    @Tag("EA-V6.1")
    void everyThingThatDecidesWhatTheAgentSaysChangesIt() throws Exception {
        Files.writeString(dir.resolve("policy.md"), "refund within 30 days");
        String base = identity(BASE);
        java.util.List<UnaryOperator<String>> edits = java.util.List.of(
                s -> s.replace("m-1", "m-2"),
                s -> s.replace("You are Triager.", "You are a careful Triager."),
                s -> s.replace("role: \"a mentor\"", "role: \"a coach\""),
                s -> s.replace("temperature: 0.2", "temperature: 0.7"),
                s -> s.replace("reasoning: string }", "reasoning: string, confidence: number }"),
                s -> s.replace("url: env.LOOKUP_URL", "url: env.OTHER_URL"),
                s -> s.replace("tools: [Lookup]", "tools: []"),
                s -> s.replace("pii: mask", "pii: block"),
                s -> s.replace("choices: approve, reject, escalate", "choices: approve, escalate, reject"),
                s -> s.replace("choices: approve", "dangerous mistake: propose approve, person decides reject choices: approve"),
                s -> s.replace("proposed by: Triager", "task: \"Decide {amount}\" proposed by: Triager"),
                s -> s.replace("persona: Mentor", ""));
        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(base);
        for (int i = 0; i < edits.size(); i++) {
            String id = changed(edits.get(i));
            assertThat(seen.add(id)).as("edit " + i + " must give a new identity").isTrue();
        }
        assertThat(seen).hasSize(edits.size() + 1);
    }

    @Test
    @Tag("EA-V6.1")
    void theContentOfASkillFileIsPartOfItButTheContentOfASecretIsNot() throws Exception {
        Files.writeString(dir.resolve("policy.md"), "refund within 30 days");
        String first = identity(BASE);
        Files.writeString(dir.resolve("policy.md"), "refund within 14 days");
        String second = identity(BASE);
        assertThat(second).isNotEqualTo(first);
        Files.delete(dir.resolve("policy.md"));
        assertThat(identity(BASE)).isNotEqualTo(second).isNotEqualTo(first);

        assertThat(changed(s -> s.replace("api_key: \"k-1\"", "api_key: \"a-completely-different-secret\""))).isEqualTo(changed(s -> s));
    }

    @Test
    @Tag("EA-V6.1")
    void aKnowledgeFolderCountsByWhatItHoldsAndAMissingAgentStillGetsAnIdentity() throws Exception {
        Path kb = Files.createDirectories(dir.resolve("kb"));
        Files.writeString(kb.resolve("a.md"), "one");
        String source = BASE.replace("tools: [Lookup]", "tools: [Lookup] knowledge: [Policy]") + "knowledge Policy { type: \"local\" path: \"kb\" }\n";
        String first = identity(source);
        Files.writeString(kb.resolve("b.md"), "two");
        assertThat(identity(source)).isNotEqualTo(first);

        var script = DecisionParseTest.parse("decision D { proposed by: Nobody choices: a, b }");
        assertThat(AgentIdentity.of(script, script.getDecisions().get(0), dir)).hasSize(64);
        assertThat(AgentIdentity.shorten(first)).hasSize(10);
        assertThat(AgentIdentity.shorten(null)).isEmpty();
    }
}
