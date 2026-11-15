package io.github.llm4j.tools.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.Isolation;
import io.github.llm4j.tools.CommandKind;
import io.github.llm4j.tools.FakeSandbox;
import io.github.llm4j.tools.SandboxRequest;
import io.github.llm4j.tools.SandboxResult;
import io.github.llm4j.tools.support.RecordingEffects;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 36c, D9 (G84): a typed command: the author's template, validated parameters, one argument per value, isolation required. */
class CommandKindTest {

    @TempDir Path dir;
    final RecordingEffects ctx = new RecordingEffects();
    final FakeSandbox sandbox = new FakeSandbox(Isolation.CONTAINER).answering(r -> new SandboxResult(0, false, "done\n", "", false, 0, Isolation.CONTAINER, 1));

    private Map<String, String> options(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("isolation", "container");
        m.put("image", "dpokidov/imagemagick:7");
        m.put("workspace.root", "work");
        m.put("run", "convert {input} -resize {percent}% {output}");
        m.put("param.input", "path in");
        m.put("param.output", "path out");
        m.put("param.percent", "int 1..400");
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    /** Options with only the given run and params: the defaults' params would be required arguments. */
    private Map<String, String> bare(String... pairs) {
        Map<String, String> m = options(pairs);
        m.keySet().removeIf(k -> k.startsWith("param.") && !java.util.Arrays.asList(pairs).contains(k));
        return m;
    }

    private Tool tool(Map<String, String> o) throws Exception {
        Files.createDirectories(dir.resolve("work"));
        Files.writeString(dir.resolve("work/in.png"), "x");
        return new CommandKind(s -> sandbox).create("Resize", o, dir, ctx);
    }

    private SandboxRequest last() {
        return sandbox.requests.get(sandbox.requests.size() - 1);
    }

    @Test
    void theTemplateIsSplitOnceAndEachValueFillsInsideOneArgument() throws Exception {
        String out = tool(options()).execute(Map.of("input", "in.png", "output", "out.png", "percent", 50));

        assertThat(out).contains("done");
        assertThat(last().program()).isEqualTo("convert");
        assertThat(last().args()).containsExactly("in.png", "-resize", "50%", "out.png");
    }

    @Test
    void aValueWithSpacesSemicolonsFlagsSubstitutionOrNewlinesStaysOneArgument() throws Exception {
        Tool tool = tool(bare("run", "echo {text}", "param.text", "string"));
        for (String nasty : new String[] {"two words", "a;b", "$(id)", "line\nbreak", "`x`", "a && b"}) {
            tool.execute(Map.of("text", nasty));
            assertThat(last().args()).as(nasty).containsExactly(nasty);
        }
    }

    @Test
    void aPathOutsideTheWorkspaceOrThroughASymlinkOrAbsoluteIsRefused() throws Exception {
        Tool tool = tool(options());
        Files.createSymbolicLink(dir.resolve("work/link.png"), dir.resolve("work/../outside.png"));
        Files.writeString(dir.resolve("outside.png"), "x");

        for (String bad : new String[] {"../outside.png", "/etc/passwd", "link.png"})
            assertThat(tool.execute(Map.of("input", bad, "output", "o.png", "percent", 10))).as(bad).startsWith("Error:");
        assertThat(sandbox.requests).isEmpty();
    }

    @Test
    void aPathInMustExist() throws Exception {
        assertThat(tool(options()).execute(Map.of("input", "missing.png", "output", "o.png", "percent", 10))).startsWith("Error:").contains("missing.png");
    }

    @Test
    void typesAndRangesAreEnforced() throws Exception {
        Tool tool = tool(options());
        assertThat(tool.execute(Map.of("input", "in.png", "output", "o.png", "percent", 500))).startsWith("Error:").contains("1..400");
        assertThat(tool.execute(Map.of("input", "in.png", "output", "o.png", "percent", "ten"))).startsWith("Error:").contains("whole number");
        assertThat(tool.execute(Map.of("input", "in.png", "output", "o.png"))).startsWith("Error:").contains("percent is required");
        assertThat(tool.execute(Map.of("input", "in.png", "output", "o.png", "percent", 5, "extra", "x"))).startsWith("Error:").contains("unknown argument extra");
    }

    @Test
    void enumNumberBoolAndStringRulesHold() throws Exception {
        Tool tool =
                tool(
                        bare(
                                "run", "tool {mode} {scale} {verbose} {name}",
                                "param.mode", "enum fast|slow",
                                "param.scale", "number 0.5..2",
                                "param.verbose", "bool \"--verbose\"",
                                "param.name", "string max=5 pattern=[a-z]+"));
        tool.execute(Map.of("mode", "fast", "scale", 1.5, "verbose", true, "name", "abc"));
        assertThat(last().args()).containsExactly("fast", "1.5", "--verbose", "abc");

        tool.execute(Map.of("mode", "slow", "scale", 1, "verbose", false, "name", "abc"));
        assertThat(last().args()).containsExactly("slow", "1", "abc");

        assertThat(tool.execute(Map.of("mode", "warp", "scale", 1, "verbose", false, "name", "abc"))).contains("fast, slow");
        assertThat(tool.execute(Map.of("mode", "fast", "scale", 3, "verbose", false, "name", "abc"))).contains("0.5..2");
        assertThat(tool.execute(Map.of("mode", "fast", "scale", 1, "verbose", false, "name", "ABC"))).contains("pattern");
        assertThat(tool.execute(Map.of("mode", "fast", "scale", 1, "verbose", false, "name", "abcdefg"))).contains("at most 5");
    }

    @Test
    void aNonZeroExitIsAFailureUnlessOkExitSaysOtherwise() throws Exception {
        FakeSandbox failing = new FakeSandbox(Isolation.CONTAINER).answering(r -> new SandboxResult(1, false, "", "bad input test-secret-123", false, 0, Isolation.CONTAINER, 1));
        Tool tool = new CommandKind(s -> failing).create("Resize", options(), dir, ctx);
        Files.createDirectories(dir.resolve("work"));
        Files.writeString(dir.resolve("work/in.png"), "x");
        Map<String, Object> args = Map.of("input", "in.png", "output", "o.png", "percent", 10);
        assertThat(tool.execute(args)).startsWith("Error:").contains("exit 1").contains("bad input");

        Tool tolerant = new CommandKind(s -> failing).create("Resize", options("ok_exit", "0,1"), dir, ctx);
        assertThat(tolerant.execute(args)).doesNotStartWith("Error:");
    }

    @Test
    void jsonAndLinesOutputsAreCheckedOrSplit() throws Exception {
        FakeSandbox json = new FakeSandbox(Isolation.CONTAINER).answering(r -> new SandboxResult(0, false, "{\"a\":1}", "", false, 0, Isolation.CONTAINER, 1));
        assertThat(new CommandKind(s -> json).create("T", options("output", "json"), dir, ctx).execute(args())).isEqualTo("{\"a\":1}");
        FakeSandbox notJson = new FakeSandbox(Isolation.CONTAINER).answering(r -> new SandboxResult(0, false, "nope", "", false, 0, Isolation.CONTAINER, 1));
        assertThat(new CommandKind(s -> notJson).create("T", options("output", "json"), dir, ctx).execute(args())).startsWith("Error:").contains("JSON");
        FakeSandbox lines = new FakeSandbox(Isolation.CONTAINER).answering(r -> new SandboxResult(0, false, "a\nb\n", "", false, 0, Isolation.CONTAINER, 1));
        assertThat(new CommandKind(s -> lines).create("T", options("output", "lines"), dir, ctx).execute(args())).isEqualTo("[\"a\",\"b\"]");
    }

    private int percent = 0;

    private Map<String, Object> args() throws IOException {
        Files.createDirectories(dir.resolve("work"));
        Files.writeString(dir.resolve("work/in.png"), "x");
        return Map.of("input", "in.png", "output", "o.png", "percent", ++percent);
    }

    @Test
    void isolationIsRequiredAndNoneIsNotAnIsolation() {
        CommandKind kind = new CommandKind(s -> sandbox);
        Map<String, String> noLevel = options();
        noLevel.remove("isolation");
        assertThat(kind.check(noLevel, dir)).contains("isolation");
        assertThat(kind.check(options("isolation", "none"), dir)).contains("isolation");
        assertThat(kind.check(options("image", ""), dir)).isNotNull();
    }

    @Test
    void theTemplateIsCheckedAtLoad() {
        CommandKind kind = new CommandKind(s -> sandbox);
        assertThat(kind.check(options("run", "convert {nope}"), dir)).contains("nope");
        assertThat(kind.check(options("run", "{input} x"), dir)).contains("program");
        assertThat(kind.check(options("run", "convert \"unclosed"), dir)).contains("quote");
        assertThat(kind.check(options("param.bad", "wat"), dir)).contains("param.bad");
        assertThat(kind.check(options(), dir)).isNull();
        assertThat(kind.check(options("run", "convert {input} {output} {percent} \"two words\""), dir)).isNull();
    }

    @Test
    void aToolWithAPathOutIsAnAssumedLocalWriteInTheAudit() {
        var p = new CommandKind(s -> sandbox).profile(options());

        assertThat(p.effect().name()).isEqualTo("CHANGES");
        assertThat(p.isolation()).isEqualTo(Isolation.CONTAINER);
        assertThat(p.note()).contains("convert");
    }

    @Test
    void aProgramThatIsNotAllowedToBeNamedByPlaceholderIsRefused() {
        assertThatThrownBy(() -> new CommandKind(s -> sandbox).create("T", options("run", "{input}"), dir, ctx)).isInstanceOf(IllegalArgumentException.class);
        assertThat(List.of(1)).isNotEmpty();
    }
}
