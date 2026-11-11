package io.github.llm4j.loom.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.cli.ScriptDrift;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.execution.StatementText;
import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The visitors of the language core handle remote calls and ask: each one a plain test. */
class A2ASurfacesTest {

    @TempDir Path dir;

    private static final String HEAD = """
            agent_channel Desk { audience: staff }
            remote Supplier { card: "https://s.example.com/card.json" agent_channel: Desk }
            """;

    private static List<ScriptValidator.Problem> check(String source) {
        return new ScriptValidator().validate(A2AParseTest.parse(source), new ScriptValidator.Context().registeredTools(java.util.Set.of()).baseDir(Path.of(".")));
    }

    @Test
    void statementTextNamesAnAsk() {
        var ask = A2AParseTest.parse("workflow W() { ask Desk \"q\" -> r }").getWorkflows().get(0).getStatements().get(0);
        assertThat(StatementText.of(ask)).isEqualTo("ask Desk");
        var call = A2AParseTest.parse(HEAD + "workflow W() { delegate \"q\" to Supplier -> r }").getWorkflows().get(0).getStatements().get(0);
        assertThat(StatementText.of(call)).isEqualTo("delegate Supplier");
    }

    @Test
    void anAnswerNobodyReadsIsLintedLikeAnyOtherUnusedResult() {
        var warnings = check(HEAD + "workflow W() { ask Desk \"q\" -> r\n note \"done\" }").stream().map(ScriptValidator.Problem::message).toList();
        assertThat(warnings).anyMatch(m -> m.contains("r is set here and never used"));
        var used = check(HEAD + "workflow W() { ask Desk \"q\" -> r\n note \"got {r}\" }").stream().map(ScriptValidator.Problem::message).toList();
        assertThat(used).noneMatch(m -> m.contains("never used"));
    }

    @Test
    void theQuestionsPlaceholdersCountAsReadingAVariable() {
        var warnings = check(HEAD + "workflow W(part) { delegate \"q\" to Supplier -> price\n ask Desk \"Is {price} ok?\" -> ok\n note \"{ok}\" }").stream().map(ScriptValidator.Problem::message).toList();
        assertThat(warnings).noneMatch(m -> m.contains("price is set here and never used"));
    }

    @Test
    void whatARemoteOrAChannelReturnsReachesAReturnAsUncheckedWhenAPolicyIsDeclared() {
        String policy = "evaluator Compliance { model: \"judge\" }\npolicy HouseRules: \"house-rules\" checked by Compliance\n";
        var viaRemote = check(policy + HEAD + "workflow W() { delegate \"q\" to Supplier -> quote\n return quote }");
        assertThat(viaRemote.stream().map(ScriptValidator.Problem::message)).anyMatch(m -> m.contains("without a check against HouseRules"));
        var viaAsk = check(policy + HEAD + "workflow W() { ask Desk \"q\" -> reply\n return reply }");
        assertThat(viaAsk.stream().map(ScriptValidator.Problem::message)).anyMatch(m -> m.contains("without a check against HouseRules"));
    }

    @Test
    void aChangeToARemotesCardOrAChannelsAudienceBeforeTheForkPointIsDrift() throws Exception {
        String body = "workflow W() { delegate \"q\" to Supplier -> quote\n ask Desk \"Which?\" -> r\n note \"{r}\" }";
        Path original = Files.writeString(dir.resolve("a.loom"), HEAD + body);
        Path same = Files.writeString(dir.resolve("b.loom"), HEAD + body);
        Path otherCard = Files.writeString(dir.resolve("c.loom"), HEAD.replace("s.example.com", "other.example.com") + body);
        Path otherAudience = Files.writeString(dir.resolve("d.loom"), HEAD.replace("audience: staff", "audience: automatic") + body);
        assertThat(ScriptDrift.between(original, same, RunJournal.inMemory(), "W", null)).isNull();
        assertThat(ScriptDrift.between(original, otherCard, RunJournal.inMemory(), "W", null)).contains("other.example.com");
        assertThat(ScriptDrift.between(original, otherAudience, RunJournal.inMemory(), "W", null)).contains("automatic");
    }
}
