package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 36a, D4 and D10 rule 3: what {@code weave mcp list --write} changes in a script is the
 * {@code effects:} and {@code pin:} of one block and nothing else, and a declared effect is a
 * person's word that a proposal never overwrites.
 */
class McpScriptEditorTest {

    static final String TAIL = "agent A { model: \"m\" system: \"s\" mcp_servers: [Files] }\nworkflow Main() { delegate \"x\" to A -> r }\n";

    private static Map<String, TaskEffect> effects(Object... pairs) {
        Map<String, TaskEffect> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (TaskEffect) pairs[i + 1]);
        return m;
    }

    private static io.github.llm4j.loom.ast.McpServerDef parsed(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript().getMcpServers().get(0);
    }

    @Test
    void addsEffectsAndAPinToAMultiLineBlock() {
        String before = "// my servers\nmcp Files {\n    transport: \"stdio\"\n    cmd: \"srv\"\n    isolation: process\n}\n" + TAIL;

        String after =
                McpScriptEditor.apply(
                        before,
                        "Files",
                        effects("read_file", TaskEffect.READS, "write_file", TaskEffect.CHANGES),
                        "sha256-0123456789abcdef");

        var block = parsed(after);
        assertThat(block.getToolEffects())
                .containsEntry("read_file", TaskEffect.READS)
                .containsEntry("write_file", TaskEffect.CHANGES);
        assertThat(block.getPin()).isEqualTo("sha256-0123456789abcdef");
        assertThat(block.getIsolation()).isEqualTo("process");
        assertThat(after).startsWith("// my servers\n").endsWith(TAIL);
    }

    @Test
    void addsToASingleLineBlock() {
        String before = "mcp Files { transport: \"stdio\" cmd: \"srv\" }\n" + TAIL;

        String after = McpScriptEditor.apply(before, "Files", effects("t", TaskEffect.READS), "sha256-aaaaaaaaaaaaaaaa");

        assertThat(parsed(after).getToolEffects()).containsEntry("t", TaskEffect.READS);
        assertThat(parsed(after).getPin()).isEqualTo("sha256-aaaaaaaaaaaaaaaa");
    }

    @Test
    void aDeclaredEffectIsNeverOverwrittenByAProposal() {
        String before = "mcp Files { cmd: \"srv\" effects: { read_file: changes } }\n" + TAIL;

        String after =
                McpScriptEditor.apply(
                        before, "Files", effects("read_file", TaskEffect.READS, "list_dir", TaskEffect.READS), "sha256-bbbbbbbbbbbbbbbb");

        assertThat(parsed(after).getToolEffects())
                .containsEntry("read_file", TaskEffect.CHANGES)
                .containsEntry("list_dir", TaskEffect.READS);
    }

    @Test
    void anOldPinIsReplacedNotDuplicated() {
        String before = "mcp Files { cmd: \"srv\" pin: \"sha256-0000000000000000\" }\n" + TAIL;

        String after = McpScriptEditor.apply(before, "Files", effects(), "sha256-cccccccccccccccc");

        assertThat(parsed(after).getPin()).isEqualTo("sha256-cccccccccccccccc");
        assertThat(after).doesNotContain("0000000000000000");
    }

    @Test
    void onlyTheNamedBlockChangesAndTheRestIsByteForByteTheSame() {
        String other = "mcp Other { cmd: \"x\" effects: { a: reads } }\n";
        String before = other + "mcp Files { cmd: \"srv\" }\n" + TAIL;

        String after = McpScriptEditor.apply(before, "Files", effects("t", TaskEffect.READS), "sha256-dddddddddddddddd");

        assertThat(after).startsWith(other).endsWith(TAIL);
    }

    @Test
    void aBraceOrQuoteInsideAStringDoesNotConfuseTheBlockEnd() {
        String before = "mcp Files { cmd: \"srv\" args: [\"}\", \"{\"] }\n" + TAIL;

        String after = McpScriptEditor.apply(before, "Files", effects("t", TaskEffect.READS), "sha256-eeeeeeeeeeeeeeee");

        assertThat(parsed(after).getArgs()).containsExactly("}", "{");
        assertThat(parsed(after).getToolEffects()).containsKey("t");
    }

    @Test
    void aMissingBlockIsAnError() {
        assertThatThrownBy(() -> McpScriptEditor.apply("workflow M() { note \"x\" }", "Files", effects(), "sha256-ffffffffffffffff"))
                .hasMessageContaining("mcp Files");
    }

    @Test
    void theDiffShowsOnlyWhatChanged() {
        String before = "mcp Files {\n    cmd: \"srv\"\n}\n";
        String after = McpScriptEditor.apply(before, "Files", effects("t", TaskEffect.READS), "sha256-1111111111111111");

        String diff = McpScriptEditor.diff(before, after);

        assertThat(diff).contains("+").contains("effects").contains("pin").doesNotContain("-    cmd");
    }

    @Test
    void aCommentedOutBlockOfTheSameNameIsNotTheBlock() {
        String before = "// mcp Files { cmd: \"old\" }\nmcp Files { cmd: \"srv\" }\n" + TAIL;

        String after = McpScriptEditor.apply(before, "Files", effects("t", TaskEffect.READS), "sha256-1212121212121212");

        assertThat(after).startsWith("// mcp Files { cmd: \"old\" }\n").contains("cmd: \"srv\"");
        assertThat(parsed(after).getPin()).isEqualTo("sha256-1212121212121212");
    }

    @Test
    void aBraceInACommentInsideTheBlockIsNotItsEnd() {
        String before = "mcp Files {\n    cmd: \"srv\" // the } here\n}\n" + TAIL;

        String after = McpScriptEditor.apply(before, "Files", effects("t", TaskEffect.READS), "sha256-3434343434343434");

        assertThat(parsed(after).getToolEffects()).containsKey("t");
        assertThat(after).endsWith(TAIL);
    }

    @Test
    void anEscapedQuoteInAStringDoesNotEndIt() {
        String before = "mcp Files { cmd: \"srv\" args: [\"a \\\" }\", \"b\"] }\n" + TAIL;

        String after = McpScriptEditor.apply(before, "Files", effects("t", TaskEffect.READS), "sha256-5656565656565656");

        assertThat(parsed(after).getArgs()).containsExactly("a \" }", "b");
    }

    @Test
    void nestedBracesInTheBlockAreBalanced() {
        String before = "mcp Files {\n    cmd: \"srv\"\n    env: { MODE: \"read\" }\n}\n" + TAIL;

        String after = McpScriptEditor.apply(before, "Files", effects("t", TaskEffect.READS), "sha256-7878787878787878");

        assertThat(parsed(after).getEnvValues()).containsKey("MODE");
        assertThat(parsed(after).getToolEffects()).containsKey("t");
    }

    @Test
    void anUnclosedBlockIsAnError() {
        assertThatThrownBy(() -> McpScriptEditor.apply("mcp Files { cmd: \"srv\"\n", "Files", effects(), "sha256-9999999999999999"))
                .hasMessageContaining("closing brace");
    }

    @Test
    void theNewLinesKeepTheIndentationOfTheBlock() {
        String before = "    mcp Files {\n        cmd: \"srv\"\n    }\n" + TAIL;

        String after = McpScriptEditor.apply(before, "Files", effects("t", TaskEffect.READS), "sha256-abababababababab");

        assertThat(after).contains("\n        effects: { t: reads }\n").contains("\n        pin: \"sha256-abababababababab\"\n    }");
    }

    @Test
    void theDiffOfTwoEqualTextsIsEmptyAndAnEditShowsExactlyTheChangedLines() {
        assertThat(McpScriptEditor.diff("a\nb\n", "a\nb\n")).isEmpty();
        assertThat(McpScriptEditor.diff("a\nb\nc\n", "a\nX\nc\n")).isEqualTo("- b\n+ X\n");
        assertThat(McpScriptEditor.diff("a\nb\n", "a\nb\nc\n")).isEqualTo("+ c\n");
        assertThat(McpScriptEditor.diff("a\nb\nc\n", "a\nc\n")).isEqualTo("- b\n");
        assertThat(McpScriptEditor.diff("x\n", "y\n")).isEqualTo("- x\n+ y\n");
    }

    @Test
    void theEffectsOfAnExistingEntryWithAQuotedNameAreKept() {
        String before = "mcp Files { cmd: \"srv\" effects: { \"read-file\": reads } }\n" + TAIL;

        String after = McpScriptEditor.apply(before, "Files", effects("other", TaskEffect.CHANGES), "sha256-cdcdcdcdcdcdcdcd");

        assertThat(parsed(after).getToolEffects()).containsEntry("read-file", TaskEffect.READS).containsEntry("other", TaskEffect.CHANGES);
    }
}
