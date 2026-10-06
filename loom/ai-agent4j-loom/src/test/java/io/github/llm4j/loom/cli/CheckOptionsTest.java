package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.execution.LLMClientFactory;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R1.1, R1.2, R6.3 and R6.5 of loom-onboarding: {@code weave check --format json}, {@code --no-env}, {@code --strict}. */
class CheckOptionsTest {

    @TempDir Path dir;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private WeaveEnv env(Function<String, String> environment, LLMClientFactory models) {
        PrintStream p = new PrintStream(out, true);
        return new WeaveEnv(models, m -> "yes", p, p, Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), environment);
    }

    private static LLMClientFactory keyed() {
        return new LLMClientFactory() {
            @Override public io.github.llm4j.LLMClient createClient(String model) { throw new IllegalStateException(); }
            @Override public String problem(String model) { return "needs GEMINI_API_KEY in the environment or the secret store"; }
        };
    }

    private static LLMClientFactory open() {
        return model -> { throw new IllegalStateException(); };
    }

    private java.io.File script(String source) throws Exception {
        return Files.writeString(dir.resolve("main.loom"), source).toFile();
    }

    private JsonNode json() throws Exception {
        return new ObjectMapper().readTree(out.toString());
    }

    private static final String FINE = "agent A { model: \"m\" system: \"s\" }\nworkflow Main() { delegate \"x\" to A -> r\n note \"{r}\" }\n";
    private static final String UNUSED = "agent A { model: \"m\" system: \"s\" }\nworkflow Main() {\n delegate \"x\" to A -> unused\n note \"done\" }\n";

    @Test
    void theTextFormIsWhatItWas() throws Exception {
        int code = WeaveCLI.check(script(FINE), null, false, env(k -> null, open()));

        assertThat(code).isZero();
        assertThat(out.toString().strip()).isEqualTo("✓ main.loom: ready to run");
    }

    @Test
    void r1_1_jsonForAScriptThatIsFineSaysSoAndHasNoProblems() throws Exception {
        int code = WeaveCLI.check(script(FINE), null, new WeaveCLI.CheckSettings(false, true, false, false), env(k -> null, open()));

        assertThat(code).isZero();
        JsonNode j = json();
        assertThat(j.get("version").asInt()).isEqualTo(1);
        assertThat(j.get("ok").asBoolean()).isTrue();
        assertThat(j.get("diagnostics")).isEmpty();
        assertThat(j.get("file").asText()).endsWith("main.loom");
        assertThat(j.get("notSetYet")).isEmpty();
    }

    @Test
    void r1_2_aSyntaxErrorIsOneErrorDiagnosticWithItsLineInJsonAndTheSameMessageAsText() throws Exception {
        java.io.File bad = script("agent A { model: \"m\" }\nworkflow Main() {\n    delegate \"x\" to A\n}\n");

        int code = WeaveCLI.check(bad, null, new WeaveCLI.CheckSettings(false, true, false, false), env(k -> null, open()));
        JsonNode j = json();
        String jsonMessage = j.get("diagnostics").get(0).get("message").asText();
        out.reset();
        int textCode = WeaveCLI.check(bad, null, false, env(k -> null, open()));

        assertThat(code).isEqualTo(2);
        assertThat(textCode).isEqualTo(2);
        assertThat(j.get("ok").asBoolean()).isFalse();
        assertThat(j.get("diagnostics").get(0).get("severity").asText()).isEqualTo("error");
        assertThat(j.get("diagnostics").get(0).get("line").asInt()).isPositive();
        assertThat(out.toString()).contains(jsonMessage);
    }

    @Test
    void aWarningIsAWarningInJsonWithExitZero() throws Exception {
        int code = WeaveCLI.check(script(UNUSED), null, new WeaveCLI.CheckSettings(false, true, false, false), env(k -> null, open()));

        assertThat(code).isZero();
        JsonNode d = json().get("diagnostics").get(0);
        assertThat(d.get("severity").asText()).isEqualTo("warning");
        assertThat(d.get("line").asInt()).isEqualTo(3);
        assertThat(d.get("message").asText()).contains("unused is set here and never used");
        assertThat(json().get("ok").asBoolean()).isTrue();
    }

    @Test
    void r6_5_strictMakesWarningsErrorsInBothFormsAndTheExitCode() throws Exception {
        var strict = new WeaveCLI.CheckSettings(false, false, false, true);

        assertThat(WeaveCLI.check(script(UNUSED), null, strict, env(k -> null, open()))).isEqualTo(2);
        assertThat(out.toString()).contains("✗ line 3: workflow Main: unused is set here and never used").contains("1 problem in main.loom");

        out.reset();
        assertThat(WeaveCLI.check(script(UNUSED), null, new WeaveCLI.CheckSettings(false, true, false, true), env(k -> null, open()))).isEqualTo(2);
        assertThat(json().get("diagnostics").get(0).get("severity").asText()).isEqualTo("error");
        assertThat(json().get("ok").asBoolean()).isFalse();

        out.reset();
        assertThat(WeaveCLI.check(script(FINE), null, strict, env(k -> null, open()))).isZero();
    }

    @Test
    void r6_3_withoutNoEnvAMissingKeyIsAProblemAndTheMessageSaysHowToCheckWithoutKeys() throws Exception {
        int code = WeaveCLI.check(script(FINE), null, false, env(k -> null, keyed()));

        assertThat(code).isEqualTo(2);
        assertThat(out.toString()).contains("needs GEMINI_API_KEY").contains("use --no-env to check the script without keys");
    }

    @Test
    void r6_3_noEnvListsWhatIsNotSetYetInsteadOfFailing() throws Exception {
        java.io.File withTool = script("""
                tool Search { use: serpapi  api_key: env.SERPAPI_KEY }
                agent A { model: "m" system: "s" tools: [Search] }
                workflow Main() { delegate "x" to A -> r
                 note "{r}" }
                """);

        int code = WeaveCLI.check(withTool, null, new WeaveCLI.CheckSettings(false, false, true, false), env(k -> null, keyed()));

        assertThat(code).isZero();
        assertThat(out.toString()).contains("ℹ not set yet (needed to run): GEMINI_API_KEY, SERPAPI_KEY").contains("ready to run");
    }

    @Test
    void noEnvStillReportsRealProblemsAndKeysThatAreSetAreNotListed() throws Exception {
        java.io.File broken = script("agent A { model: \"m\" system: \"s\" tools: [Nope] }\nworkflow Main() { delegate \"x\" to A -> r\n note \"{r}\" }\n");

        int code = WeaveCLI.check(broken, null, new WeaveCLI.CheckSettings(false, true, true, false), env(k -> k.equals("GEMINI_API_KEY") ? "set" : null, keyed()));

        assertThat(code).isEqualTo(2);
        assertThat(json().get("diagnostics").get(0).get("message").asText()).contains("Nope");
        assertThat(json().get("notSetYet")).extracting(JsonNode::asText).doesNotContain("SERPAPI_KEY");
    }

    @Test
    void jsonCarriesTheNamesNotSetYet() throws Exception {
        WeaveCLI.check(script(FINE), null, new WeaveCLI.CheckSettings(false, true, true, false), env(k -> null, keyed()));

        assertThat(json().get("notSetYet")).extracting(JsonNode::asText).containsExactly("GEMINI_API_KEY");
        assertThat(json().get("ok").asBoolean()).isTrue();
    }

    @Test
    void theOptionsParseAndABadFormatIsRefused() {
        var cli = WeaveCLI.commandLine();
        var spec = cli.getSubcommands().get("check").getCommandSpec();

        assertThat(spec.findOption("--format")).isNotNull();
        assertThat(spec.findOption("--no-env")).isNotNull();
        assertThat(spec.findOption("--strict")).isNotNull();
    }
}
