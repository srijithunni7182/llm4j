package io.github.llm4j.loom.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.ScriptValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 26c, R8: {@code weave check} follows a model-written value to the exit and says where. */
class StaticSinkPathErrorsTest {

    @TempDir Path dir;

    static final String HEAD =
            "evaluator E { model: \"m\" }\npolicy P: \"house-rules\" checked by E\n"
                    + "agent W { model: \"m\" system: \"s\" }\n"
                    + "extractor X { model: \"m\" fields { text: string  n: number  kind: enum[\"a\", \"b\"] } }\n";

    List<String> errors(String workflows) throws Exception {
        Files.createDirectories(dir.resolve("policies"));
        Files.writeString(dir.resolve("policies/house-rules.md"), "## r_one\nBreaking it.\n");
        Files.writeString(dir.resolve("policies/legal.md"), "## r_two\nBreaking it.\n");
        var s = PolicyParsesTest.parse(HEAD + workflows);
        return new ScriptValidator()
                .validate(s, new ScriptValidator.Context().baseDir(dir)).stream()
                        .filter(p -> p.severity() == ScriptValidator.Severity.ERROR)
                        .map(p -> p.line() + " " + p.construct() + ": " + p.message())
                        .toList();
    }

    @Test
    void anUncheckedAnswerReachingTheReturnIsAnErrorNamingBothLines() throws Exception {
        List<String> e = errors("workflow Main(q) {\n  delegate \"{q}\" to W -> reply\n  return reply\n}\n");
        assertThat(e).singleElement().satisfies(m -> assertThat(m).contains("W, line").contains("without a check against P").contains("check <variable> against P"));
    }

    @Test
    void aCheckBeforeTheReturnClearsIt() throws Exception {
        assertThat(errors("workflow Main(q) {\n  delegate \"{q}\" to W -> reply\n  check reply against P using E\n  return reply\n}\n")).isEmpty();
    }

    @Test
    void everyRouteTheRuntimeStopsIsFoundStatically() throws Exception {
        for (String body : new String[] {
            "set a = reply\n  return a",
            "set a = \"Dear {reply}\"\n  return a",
            "set a = { t: reply }\n  return a",
            "return { t: reply }",
            "extract X from reply -> x\n  return x.text",
            "call Echo(m = reply) -> got\n  return got",
            "alt (q == \"a\") { set out = reply } else { set out = \"fixed\" }\n  return out"
        }) {
            List<String> e = errors("workflow Echo(m) { return m }\nworkflow Main(q) {\n  delegate \"{q}\" to W -> reply\n  " + body + "\n}\n");
            assertThat(e).as(body).anyMatch(m -> m.contains("without a check"));
        }
    }

    @Test
    void whatNoModelWroteIsNotFlagged() throws Exception {
        assertThat(errors("workflow Main(q) {\n  delegate \"{q}\" to W -> reply\n  extract X from reply -> x\n  return x.n\n}\n")).isEmpty();
        assertThat(errors("workflow Main(q) {\n  set a = \"We are here. {q}\"\n  return a\n}\n")).isEmpty();
    }

    @Test
    void aValueMustPassEveryPolicyAndTheErrorNamesTheOnesMissing() throws Exception {
        String two = "policy L: \"legal\" checked by E\n";
        List<String> e = errors(two + "workflow Main(q) {\n  delegate \"{q}\" to W -> reply\n  check reply against P using E\n  return reply\n}\n");
        assertThat(e).singleElement().satisfies(m -> assertThat(m).contains("against L"));
        assertThat(errors(two + "workflow Main(q) {\n  delegate \"{q}\" to W -> reply\n  check reply against P, L using E\n  return reply\n}\n")).isEmpty();
    }

    @Test
    void aCalledWorkflowsReturnIsItsCallersToJudge() throws Exception {
        List<String> e = errors("workflow Sub(q) {\n  delegate \"{q}\" to W -> r\n  return r\n}\nworkflow Main(q) {\n  call Sub(q = q) -> got\n  check got against P using E\n  return got\n}\n");
        assertThat(e).isEmpty();
    }

    @Test
    void aScriptWithNoPolicyIsNotChecked() throws Exception {
        var s = PolicyParsesTest.parse("agent W { model: \"m\" system: \"s\" }\nworkflow Main(q) {\n  delegate \"{q}\" to W -> r\n  return r\n}\n");
        assertThat(new ScriptValidator().validate(s, new ScriptValidator.Context().baseDir(dir)).stream().filter(p -> p.message().contains("without a check"))).isEmpty();
    }
}
