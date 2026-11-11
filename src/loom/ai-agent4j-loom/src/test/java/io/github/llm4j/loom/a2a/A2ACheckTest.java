package io.github.llm4j.loom.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.ScriptValidator;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** weave check for remote, agent_channel, expose and ask: each mistake with its line and what to write, and a warning for every remote. */
class A2ACheckTest {

    private static final String BASE = """
            agent Reviewer { model: "gemini-2.0-flash" system: "s" }
            agent_channel Desk { audience: staff max_questions: 3 timeout: 1h }
            remote Supplier {
                card: "https://supplier.example.com/.well-known/agent-card.json"
                auth: secret.SUPPLIER_KEY
                agent_channel: Desk
                timeout: 2h
            }
            """;

    private static List<ScriptValidator.Problem> check(String source) {
        var script = A2AParseTest.parse(source);
        var context = new ScriptValidator.Context().registeredTools(java.util.Set.of()).baseDir(Path.of("."));
        return new ScriptValidator().validate(script, context);
    }

    private static List<String> errors(String source) {
        return check(source).stream().filter(p -> p.severity() == ScriptValidator.Severity.ERROR).map(p -> p.line() + ": " + p.message()).toList();
    }

    private static List<String> warnings(String source) {
        return check(source).stream().filter(p -> p.severity() == ScriptValidator.Severity.WARNING).map(ScriptValidator.Problem::message).toList();
    }

    @Test
    void aWellFormedCallingScriptHasNoErrorsAndOneWarningForTheRemoteAndOneForTheChannel() {
        String source = BASE + "workflow Buy(part) { delegate \"Quote {part}\" to Supplier -> quote  ask Desk \"Which?\" -> r }";
        assertThat(errors(source)).isEmpty();
        assertThat(warnings(source)).anyMatch(w -> w.contains("remote") && w.contains("not validated"))
                .anyMatch(w -> w.contains("Desk") && w.contains("host"));
    }

    @Test
    void aRemoteNeedsACardAndAChannelAndTheCardMustBeHttps() {
        assertThat(errors(BASE + "remote A { agent_channel: Desk }\nworkflow W() { note \"x\" }")).anyMatch(e -> e.contains("card"));
        assertThat(errors(BASE + "remote A { card: \"https://a.example.com/c.json\" }\nworkflow W() { note \"x\" }")).anyMatch(e -> e.contains("agent_channel"));
        assertThat(errors(BASE + "remote A { card: \"http://a.example.com/c.json\" agent_channel: Desk }\nworkflow W() { note \"x\" }")).anyMatch(e -> e.contains("https"));
    }

    @Test
    void aLiteralAuthIsAnErrorAndASecretOrEnvReferenceIsFine() {
        assertThat(errors(BASE + "remote A { card: \"https://a.example.com/c.json\" agent_channel: Desk auth: \"sk-live-123\" }\nworkflow W() { note \"x\" }"))
                .anyMatch(e -> e.contains("auth") && e.contains("secret."));
        assertThat(errors(BASE + "remote A { card: \"https://a.example.com/c.json\" agent_channel: Desk auth: env.A_KEY }\nworkflow W() { note \"x\" }")).isEmpty();
    }

    @Test
    void thereIsNoVersionSettingAndNoPiiSettingAndUnknownSettingsAreRefused() {
        assertThat(errors(BASE + "remote A { card: \"https://a.example.com/c.json\" agent_channel: Desk version: \"1.0\" }\nworkflow W() { note \"x\" }"))
                .anyMatch(e -> e.contains("version") && e.contains("transport"));
        assertThat(errors(BASE + "remote A { card: \"https://a.example.com/c.json\" agent_channel: Desk pii: mask }\nworkflow W() { note \"x\" }"))
                .anyMatch(e -> e.contains("pii"));
        assertThat(errors(BASE + "remote A { card: \"https://a.example.com/c.json\" agent_channel: Desk colour: red }\nworkflow W() { note \"x\" }"))
                .anyMatch(e -> e.contains("colour") && e.contains("card"));
    }

    @Test
    void timeoutAndRequireSignedCardMustBeWhatTheyClaim() {
        assertThat(errors(BASE + "remote A { card: \"https://a.example.com/c.json\" agent_channel: Desk timeout: soon }\nworkflow W() { note \"x\" }")).anyMatch(e -> e.contains("timeout"));
        assertThat(errors(BASE + "remote A { card: \"https://a.example.com/c.json\" agent_channel: Desk require_signed_card: maybe }\nworkflow W() { note \"x\" }")).anyMatch(e -> e.contains("require_signed_card") && e.contains("true"));
        assertThat(errors(BASE + "remote A { card: \"https://a.example.com/c.json\" agent_channel: Desk require_signed_card: true }\nworkflow W() { note \"x\" }")).isEmpty();
    }

    @Test
    void aRemoteOrAskNamingAnUndeclaredChannelIsAnErrorThatSaysWhatIsDeclared() {
        assertThat(errors(BASE + "remote A { card: \"https://a.example.com/c.json\" agent_channel: Nope }\nworkflow W() { note \"x\" }")).anyMatch(e -> e.contains("Nope") && e.contains("Desk"));
        assertThat(errors(BASE + "workflow W() { ask Nope \"q\" -> r }")).anyMatch(e -> e.contains("Nope") && e.contains("Desk"));
    }

    @Test
    void aChannelNeedsAKnownAudienceAndSaneLimits() {
        assertThat(errors("agent_channel C { max_questions: 3 }\nworkflow W() { note \"x\" }")).anyMatch(e -> e.contains("audience"));
        assertThat(errors("agent_channel C { audience: everyone }\nworkflow W() { note \"x\" }")).anyMatch(e -> e.contains("everyone") && e.contains("staff"));
        assertThat(errors("agent_channel C { audience: caller max_questions: 0 }\nworkflow W() { note \"x\" }")).anyMatch(e -> e.contains("max_questions"));
        assertThat(errors("agent_channel C { audience: automatic timeout: forever }\nworkflow W() { note \"x\" }")).anyMatch(e -> e.contains("timeout"));
    }

    @Test
    void aDelegateToARemoteMayNotCarryATokenBudgetAndAttachNeedsAMediaRoot() {
        assertThat(errors(BASE + "workflow W() { delegate \"x\" to Supplier -> r budget 5000 tokens }")).anyMatch(e -> e.contains("budget") && e.contains("outside"));
        assertThat(errors(BASE + "workflow W(f) { delegate \"x\" to Supplier attach document \"{f}\" -> r }")).anyMatch(e -> e.contains("media_root"));
    }

    @Test
    void aDynamicTargetListNamesOnlyRemotesOrOnlyLocalAgentsNeverAMix() {
        assertThat(errors(BASE + "workflow W(t) { delegate \"x\" to {t} in [Supplier, Reviewer] -> r }")).anyMatch(e -> e.contains("mix") || e.contains("both"));
        assertThat(errors(BASE + "workflow W(t) { delegate \"x\" to {t} in [Reviewer] -> r }")).noneMatch(e -> e.contains("mix"));
    }

    @Test
    void handoffBroadcastAndExtractDoNotTakeARemote() {
        assertThat(errors(BASE + "workflow W() { handoff \"x\" to Supplier }")).anyMatch(e -> e.contains("handoff") && e.contains("remote"));
        assertThat(errors(BASE + "workflow W() { broadcast \"x\" to [Supplier, Reviewer] -> r }")).anyMatch(e -> e.contains("broadcast") && e.contains("parallel"));
    }

    @Test
    void aDelegateToAnUnknownNameStillSaysThereIsNoSuchAgent() {
        assertThat(errors(BASE + "workflow W() { delegate \"x\" to Nobody -> r }")).anyMatch(e -> e.contains("Nobody"));
    }

    // ---- expose ----

    private static final String EXPOSED = BASE + """
            workflow Expense(receipt) { delegate "read {receipt}" to Reviewer -> total }
            """;

    @Test
    void aWellFormedExposeHasNoErrors() {
        assertThat(errors(EXPOSED + "expose E { workflow: Expense description: \"Reads a receipt.\" agent_channel: Desk text_input: receipt max_input: 32k timeout: 30m budget { tokens: 1000 } }")).isEmpty();
    }

    @Test
    void anExposeNeedsAWorkflowADescriptionAndAChannelAndTheWorkflowMustExist() {
        assertThat(errors(EXPOSED + "expose E { description: \"d\" agent_channel: Desk }")).anyMatch(e -> e.contains("workflow"));
        assertThat(errors(EXPOSED + "expose E { workflow: Expense agent_channel: Desk }")).anyMatch(e -> e.contains("description"));
        assertThat(errors(EXPOSED + "expose E { workflow: Expense description: \"d\" }")).anyMatch(e -> e.contains("agent_channel"));
        assertThat(errors(EXPOSED + "expose E { workflow: Nope description: \"d\" agent_channel: Desk }")).anyMatch(e -> e.contains("Nope") && e.contains("Expense"));
        assertThat(errors(EXPOSED + "expose E { workflow: Expense description: \"d\" agent_channel: Missing }")).anyMatch(e -> e.contains("Missing"));
    }

    @Test
    void textInputMustNameAParameterAndMaxInputMustBeASize() {
        assertThat(errors(EXPOSED + "expose E { workflow: Expense description: \"d\" agent_channel: Desk text_input: nope }")).anyMatch(e -> e.contains("text_input") && e.contains("receipt"));
        assertThat(errors(EXPOSED + "expose E { workflow: Expense description: \"d\" agent_channel: Desk max_input: big }")).anyMatch(e -> e.contains("max_input"));
    }

    @Test
    void severalExposeBlocksMayNameTheSameWorkflow() {
        String two = EXPOSED + "expose A { workflow: Expense description: \"d\" agent_channel: Desk }\nexpose B { workflow: Expense description: \"e\" agent_channel: Desk }";
        assertThat(errors(two)).isEmpty();
    }

    @Test
    void errorsCarryTheLineOfTheMistake() {
        var problems = check(BASE + "remote A { card: \"http://a.example.com\"\n agent_channel: Desk }\nworkflow W() { note \"x\" }");
        assertThat(problems.stream().filter(p -> p.severity() == ScriptValidator.Severity.ERROR)).allMatch(p -> p.line() > 0);
    }
}
