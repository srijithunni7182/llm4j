package io.github.llm4j.tools.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.Isolation;
import io.github.llm4j.agent.tool.ToolKind;
import io.github.llm4j.tools.CommandKind;
import io.github.llm4j.tools.FakeSandbox;
import io.github.llm4j.tools.SandboxResult;
import io.github.llm4j.tools.support.RecordingEffects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 36c, D9: the declaration a {@code command} kind makes to its host (names, references, profile, what is
 * unchecked), the option errors it names, and the edges of each parameter type.
 */
class CommandKindContractTest {

    @TempDir Path dir;
    final RecordingEffects ctx = new RecordingEffects();
    final FakeSandbox sandbox = new FakeSandbox(Isolation.CONTAINER).answering(r -> new SandboxResult(0, false, "ok", "", false, 0, Isolation.CONTAINER, 1));
    final CommandKind kind = new CommandKind(s -> sandbox);

    private Map<String, String> options(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("isolation", "container");
        m.put("image", "img:1");
        m.put("workspace.root", "work");
        m.put("run", "tool {x}");
        m.put("param.x", "string");
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] == null) m.remove(pairs[i]);
            else m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private Tool tool(String run, String param) throws Exception {
        Files.createDirectories(dir.resolve("work"));
        Files.writeString(dir.resolve("work/in.png"), "x");
        return kind.create("T", options("run", run, "param.x", param), dir, ctx);
    }

    @Test
    void theKindDeclaresItsNamesReferencesAndWhatIsUnchecked() {
        assertThat(kind.name()).isEqualTo("command");
        assertThat(kind.required()).containsExactlyInAnyOrder("run", "isolation");
        assertThat(kind.optional()).contains("image", "runtime", "pull", "workspace", "network", "hosts", "timeout", "max_output", "output", "ok_exit", "on_unknown").doesNotContain("run");
        assertThat(kind.prefixes()).containsExactlyInAnyOrder("param.", "workspace.");
        assertThat(kind.references()).containsExactly(Map.entry("workspace", "workspace"));
        assertThat(kind.buildsAtRunStart()).isTrue();
        assertThat(kind.uncheckedResult(Map.of())).containsExactly(ToolKind.WHOLE_RESULT);
    }

    @Test
    void anAgentMustHaveTheCommandApproved() {
        assertThat(kind.agentProblem(Map.of(), "Resize", "Editor", true)).isNull();
        assertThat(kind.agentProblem(Map.of(), "Resize", "Editor", false)).contains("Resize").contains("Editor").contains("approve:");
    }

    @Test
    void theProfileSaysWhatTheCommandReaches() {
        var container = kind.profile(Map.of("isolation", "container", "run", "convert {a}", "network", "none"));
        assertThat(container.isolation()).isEqualTo(Isolation.CONTAINER);
        assertThat(container.outward()).isFalse();
        assertThat(container.journaled()).isTrue();
        assertThat(container.note()).contains("convert").contains("container").doesNotContain("with network");
        var open = kind.profile(Map.of("isolation", "process", "run", "x", "network", "open"));
        assertThat(open.isolation()).isEqualTo(Isolation.PROCESS);
        assertThat(open.outward()).isTrue();
        assertThat(open.note()).contains("with network");
        assertThat(kind.profile(Map.of("isolation", "vm", "run", "x")).isolation()).isEqualTo(Isolation.VM);
        assertThat(kind.profile(Map.of("run", "x")).isolation()).as("no isolation is none").isEqualTo(Isolation.NONE);
        assertThat(kind.profile(Map.of("isolation", "process", "run", "x")).outward()).as("no network option is none").isFalse();
    }

    @Test
    void everyOptionErrorNamesTheOptionAndTheWayOut() {
        assertThat(kind.check(options("isolation", null), dir)).contains("isolation:");
        assertThat(kind.check(options("isolation", "none"), dir)).contains("isolation:");
        assertThat(kind.check(options("image", null), dir)).contains("image: is required");
        assertThat(kind.check(options("image", " "), dir)).contains("image: is required");
        assertThat(kind.check(options("isolation", "process", "network", "open"), dir)).contains("image:, runtime: and pull:");
        assertThat(kind.check(options("isolation", "process", "network", "open", "image", null, "pull", "true"), dir)).contains("image:, runtime: and pull:");
        assertThat(kind.check(options("workspace.root", null), dir)).contains("workspace:");
        assertThat(kind.check(options("ok_exit", "0,x"), dir)).contains("x is not an exit code");
        assertThat(kind.check(options("output", "xml"), dir)).contains("output");
        assertThat(kind.check(options("run", "tool {y}"), dir)).contains("{y}");
        assertThat(kind.check(options(), dir)).isNull();
        assertThat(kind.check(options("ok_exit", " 0 , 3 "), dir)).as("exit codes are trimmed").isNull();
        assertThat(kind.check(options("workspace.root", null, "workspace", "Data"), dir)).as("a named workspace tool").isNull();
    }

    @Test
    void aBackendBelowTheDeclaredLevelAndOneThatCannotKeepTheNetworkOffAreRefused() {
        CommandKind weak = new CommandKind(s -> new FakeSandbox(Isolation.PROCESS).answering(r -> new SandboxResult(0, false, "", "", false, 0, Isolation.PROCESS, 1)));
        assertThatThrownBy(() -> weak.create("T", options(), dir, ctx)).hasMessageContaining("below the container");
        CommandKind noNetworkControl = new CommandKind(s -> new FakeSandbox(Isolation.CONTAINER, java.util.EnumSet.noneOf(io.github.llm4j.tools.SandboxBackend.Limit.class)).answering(r -> new SandboxResult(0, false, "", "", false, 0, Isolation.CONTAINER, 1)));
        assertThatThrownBy(() -> noNetworkControl.create("T", options(), dir, ctx)).hasMessageContaining("cannot keep a command off the network");
    }

    @Test
    void anIntegerRangeIncludesBothEndsAndRefusesAFractionAndABoolean() throws Exception {
        Tool t = tool("tool {x}", "int 1..5");
        assertThat(t.execute(Map.of("x", 1))).doesNotStartWith("Error:");
        assertThat(t.execute(Map.of("x", 5))).doesNotStartWith("Error:");
        assertThat(t.execute(Map.of("x", 0))).startsWith("Error:").contains("1..5");
        assertThat(t.execute(Map.of("x", 6))).startsWith("Error:").contains("1..5");
        assertThat(t.execute(Map.of("x", 2.5))).startsWith("Error:").contains("whole number");
        assertThat(t.execute(Map.of("x", true))).startsWith("Error:");
        assertThat(t.execute(Map.of("x", "3"))).as("a number written as text is the number").doesNotStartWith("Error:");
    }

    @Test
    void aNumberRangeIncludesBothEndsAndRefusesText() throws Exception {
        Tool t = tool("tool {x}", "number 0.5..2");
        assertThat(t.execute(Map.of("x", 0.5))).doesNotStartWith("Error:");
        assertThat(t.execute(Map.of("x", 2))).doesNotStartWith("Error:");
        assertThat(t.execute(Map.of("x", 0.4))).startsWith("Error:").contains("0.5..2");
        assertThat(t.execute(Map.of("x", 2.1))).startsWith("Error:").contains("0.5..2");
        assertThat(t.execute(Map.of("x", "much"))).startsWith("Error:");
        assertThat(t.execute(Map.of("x", false))).startsWith("Error:");
    }

    @Test
    void aFlagAddsItsTextOnlyWhenTrueAndNothingWhenFalseOrMissing() throws Exception {
        Tool t = tool("tool {x} end", "bool \"--verbose\"");
        t.execute(Map.of("x", true));
        assertThat(sandbox.requests.get(sandbox.requests.size() - 1).args()).containsExactly("--verbose", "end");
        t.execute(Map.of("x", false));
        assertThat(sandbox.requests.get(sandbox.requests.size() - 1).args()).containsExactly("end");
        t.execute(Map.of());
        assertThat(sandbox.requests.get(sandbox.requests.size() - 1).args()).containsExactly("end");
        assertThat(t.execute(Map.of("x", "maybe"))).startsWith("Error:");
    }

    @Test
    void aQuotedWordStaysOneWordAndAnOpenQuoteIsRefused() throws Exception {
        Tool t = tool("tool \"two words\" {x} \"\"", "string");
        t.execute(Map.of("x", "v"));
        assertThat(sandbox.requests.get(sandbox.requests.size() - 1).args()).containsExactly("two words", "v", "");
        assertThat(kind.check(options("run", "tool \"open {x}"), dir)).contains("opening quote");
        assertThat(kind.check(options("run", "   "), dir)).contains("run:");
        assertThat(kind.check(options("run", "{x} tool"), dir)).contains("program");
    }

    @Test
    void everyTypeNameIsKnownAndAMalformedSpecIsRefusedNamingTheParameter() {
        for (String bad : new String[] {"path", "path both", "int", "int 5", "int a..b", "number 1", "enum", "enum a||b", "bool", "bool one two", "string pattern=[", "colour"})
            assertThat(kind.check(options("param.x", bad), dir)).as(bad).contains("param.x");
        for (String good : new String[] {"path in", "path out", "int -5..5", "number 0.5..2.5", "enum a|b", "string", "string max=5", "string pattern=[a-z]+ max=5", "bool \"-v\""})
            assertThat(kind.check(options("param.x", good, "run", "tool {x}"), dir)).as(good).isNull();
    }

    @Test
    void theToolDescribesItsParametersToTheModel() throws Exception {
        Tool t = tool("tool {x}", "int 1..5");
        assertThat(t.getName()).isEqualTo("T");
        assertThat(t.getDescription()).contains("tool");
        var schema = t.getParametersSchema();
        assertThat(schema).containsEntry("type", "object").containsEntry("additionalProperties", false);
        assertThat(schema.get("properties")).isEqualTo(Map.of("x", Map.of("type", "integer")));
        assertThat(tool("tool {x}", "number 1..2").getParametersSchema().get("properties")).isEqualTo(Map.of("x", Map.of("type", "number")));
        assertThat(tool("tool {x}", "bool \"-v\"").getParametersSchema().get("properties")).isEqualTo(Map.of("x", Map.of("type", "boolean")));
        assertThat(tool("tool {x}", "string").getParametersSchema().get("properties")).isEqualTo(Map.of("x", Map.of("type", "string")));
    }

    @Test
    void theTemplateReadsBackAsLowerCaseText() throws Exception {
        assertThat(kind.check(options("run", "Tool {x}"), dir)).isNull();
    }

    @Test
    void aValueThatStartsWithADashIsRefusedUnlessTheAuthorAllowsIt() throws Exception {
        Tool plain = tool("tool {x}", "string");
        assertThat(plain.execute(Map.of("x", "--output=../../x"))).startsWith("Error:").contains("must not start with -");
        assertThat(plain.execute(Map.of("x", "ok-dash"))).doesNotStartWith("Error:");
        Tool opted = tool("tool {x}", "string allow_dash");
        assertThat(opted.execute(Map.of("x", "-v"))).doesNotStartWith("Error:");
    }

    @Test
    void aPathThatStartsWithADashIsWrittenSoNoProgramReadsItAsAnOption() throws Exception {
        Tool t = tool("tool {x}", "path in");
        Files.writeString(dir.resolve("work/-rf"), "x");
        t.execute(Map.of("x", "-rf"));
        assertThat(sandbox.requests.get(sandbox.requests.size() - 1).args()).containsExactly("./-rf");
        Tool out = tool("tool {x}", "path out");
        out.execute(Map.of("x", "-new"));
        assertThat(sandbox.requests.get(sandbox.requests.size() - 1).args()).containsExactly("./-new");
    }

    @Test
    void notANumberIsOutOfEveryRange() throws Exception {
        Tool t = tool("tool {x}", "number 0.5..2");
        assertThat(t.execute(Map.of("x", Double.NaN))).startsWith("Error:");
        assertThat(t.execute(Map.of("x", "NaN"))).startsWith("Error:");
    }
}
