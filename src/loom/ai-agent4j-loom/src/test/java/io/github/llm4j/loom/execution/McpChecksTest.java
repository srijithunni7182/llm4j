package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every way an mcp block can be wrong or risky, at the severity it is raised (phase 36a, D7, G80). */
class McpChecksTest {

    private static List<ScriptValidator.Problem> check(String block, Map<String, String> env) {
        var script = new LoomParser(new Lexer(block + "\nworkflow Main() { note \"x\" }").tokenize()).parseScript();
        return new ScriptValidator()
                .validate(script, new ScriptValidator.Context().registeredTools(java.util.Set.of()).env(env::get));
    }

    private static List<String> messages(List<ScriptValidator.Problem> problems, ScriptValidator.Severity severity) {
        return problems.stream().filter(p -> p.severity() == severity).map(ScriptValidator.Problem::message).toList();
    }

    @Test
    void aBlockWithNoCmdIsAnError() {
        assertThat(messages(check("mcp F { transport: \"stdio\" }", Map.of()), ScriptValidator.Severity.ERROR))
                .anyMatch(m -> m.contains("needs cmd"));
        assertThat(messages(check("mcp F { cmd: \"  \" }", Map.of()), ScriptValidator.Severity.ERROR))
                .anyMatch(m -> m.contains("needs cmd"));
    }

    @Test
    void noIsolationLineIsAWarningNamingTheCommandAndTheFix() {
        var problems = check("mcp F { cmd: \"npx -y srv\" }", Map.of());

        assertThat(messages(problems, ScriptValidator.Severity.ERROR)).isEmpty();
        assertThat(messages(problems, ScriptValidator.Severity.WARNING))
                .singleElement()
                .satisfies(m -> assertThat(m).contains("npx -y srv").contains("isolation: process").contains("isolation: none"));
    }

    @Test
    void envInheritWithoutAnIsolationLineIsAnError() {
        assertThat(messages(check("mcp F { cmd: \"srv\" env_inherit: [\"PATH\"] }", Map.of()), ScriptValidator.Severity.ERROR))
                .anyMatch(m -> m.contains("env_inherit") && m.contains("isolation"));
    }

    @Test
    void aLevelOtherThanNoneOrProcessIsAnError() {
        assertThat(messages(check("mcp F { cmd: \"srv\" isolation: container }", Map.of()), ScriptValidator.Severity.ERROR))
                .singleElement()
                .satisfies(m -> assertThat(m).contains("container").contains("none or process"));
    }

    @Test
    void withAnIsolationLineCmdIsOneProgram() {
        assertThat(messages(check("mcp F { cmd: \"npx -y srv\" isolation: process }", Map.of()), ScriptValidator.Severity.ERROR))
                .singleElement()
                .satisfies(m -> assertThat(m).contains("one program").contains("args:"));
        assertThat(messages(check("mcp F { cmd: \"npx\" args: [\"-y\", \"srv\"] isolation: process }", Map.of()), ScriptValidator.Severity.ERROR)).isEmpty();
        assertThat(messages(check("mcp F { cmd: \"npx\" isolation: none }", Map.of()), ScriptValidator.Severity.WARNING)).isEmpty();
    }

    @Test
    void aVariableNameMustBeAVariableName() {
        assertThat(messages(check("mcp F { cmd: \"srv\" isolation: process env: { BAD.NAME: \"x\" } }", Map.of()), ScriptValidator.Severity.ERROR))
                .singleElement()
                .satisfies(m -> assertThat(m).contains("BAD.NAME").contains("not a variable name"));
    }

    @Test
    void aVariableCannotComeFromTheInvokerBecauseAProgramTakesItsCredentialsWhenItStarts() {
        assertThat(messages(check("mcp F { cmd: \"srv\" isolation: process env: { API_TOKEN: invoker.token } }", Map.of()), ScriptValidator.Severity.ERROR))
                .singleElement()
                .satisfies(m -> assertThat(m).contains("API_TOKEN").contains("invoker.token").contains("env.NAME or secret.NAME"));
    }

    @Test
    void aCredentialLookingNameMustBeAReferenceWhateverItsSpelling() {
        for (String name : new String[] {"API_KEY", "github_token", "DB_PASSWORD", "MY_SECRET", "AUTHORIZATION", "PASSWD"})
            assertThat(messages(check("mcp F { cmd: \"srv\" isolation: process env: { " + name + ": \"literal-value\" } }", Map.of()), ScriptValidator.Severity.ERROR))
                    .as(name)
                    .singleElement()
                    .satisfies(m -> assertThat(m).contains(name).contains("secret."));
        assertThat(messages(check("mcp F { cmd: \"srv\" isolation: process env: { MODE: \"read\", REGION: \"eu\" } }", Map.of()), ScriptValidator.Severity.ERROR)).isEmpty();
    }

    @Test
    void aReferenceThatResolvesToNothingIsAnErrorNamingWhichKind() {
        assertThat(messages(check("mcp F { cmd: \"srv\" isolation: process env: { A_KEY: env.NOT_SET } }", Map.of()), ScriptValidator.Severity.ERROR))
                .singleElement()
                .satisfies(m -> assertThat(m).contains("NOT_SET").contains("for env: A_KEY"));
        assertThat(messages(check("mcp F { cmd: \"srv\" isolation: process env: { A_KEY: secret.NOPE } }", Map.of()), ScriptValidator.Severity.ERROR))
                .singleElement()
                .satisfies(m -> assertThat(m).contains("secret NOPE").contains("secret store"));
        assertThat(messages(check("mcp F { cmd: \"srv\" isolation: process env: { A_KEY: env.SET_ONE } }", Map.of("SET_ONE", "value-1234")), ScriptValidator.Severity.ERROR)).isEmpty();
    }

    @Test
    void theEnvironmentOfAServerIsItsLiteralsAndItsResolvedReferences() {
        var def = new LoomParser(new Lexer("mcp F { cmd: \"srv\" isolation: process env: { MODE: \"read\", A_KEY: env.K } }\nworkflow M() { note \"x\" }").tokenize()).parseScript().getMcpServers().get(0);

        assertThat(McpChecks.environmentOf(def, key -> key.equals("K") ? "resolved" : null)).containsEntry("MODE", "read").containsEntry("A_KEY", "resolved");
    }
}
