package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 22 R5.1: the lint covers every position the runtime substitutes a variable in. The
 * positions are read from the runtime's own calls, so a new one cannot be forgotten.
 */
class SubstitutedPositionsTest {

    private static LoomScript parse(String s) {
        return new LoomParser(new Lexer(s).tokenize()).parseScript();
    }

    private static final String AGENT = "agent A { model: \"gemini-2.5-flash\" }\n";

    private static List<String> lint(String body) {
        LoomScript script = parse(AGENT + "workflow W(out) {\n" + body + "\n}\n");
        return WorkflowLint.check(script.getWorkflows().get(0), script).stream()
                .map(WorkflowLint.Finding::message)
                .toList();
    }

    @Test
    void theWordInsideAFunctionNameInANoteIsReported() {
        assertThat(lint("  note \"length-out-of-range(x)\""))
                .anyMatch(m -> m.contains("the variable out is also written as an ordinary word"));
    }

    @Test
    void anArgumentThatIsExactlyAVariableIsAReferenceNotAnOrdinaryWord() {
        assertThat(lint("  call Other(out=out) -> r")).noneMatch(m -> m.contains("ordinary word"));
        assertThat(lint("  call Other(out=\"the out value\") -> r"))
                .anyMatch(m -> m.contains("ordinary word"));
    }

    @Test
    void everyPositionTheRuntimeSubstitutesIsScanned() {
        for (String statement :
                List.of(
                        "halt \"stopped: out of range\"",
                        "set m = \"the out value\"",
                        "set o = { text: \"the out value\" }",
                        "return \"the out value\"",
                        "observe \"out of range\" {out}",
                        "delegate \"the out value\" to A -> r",
                        "handoff \"the out value\" to A",
                        "broadcast \"the out value\" to [A] -> r",
                        "human_prompt \"is the out value ok?\" -> ok")) {
            assertThat(lint("  " + statement))
                    .as(statement)
                    .anyMatch(
                            m ->
                                    m.contains(
                                            "the variable out is also written as an ordinary word"));
        }
    }

    @Test
    void aBraceIsNotAnOrdinaryWord() {
        assertThat(lint("  note \"the value is {out}\""))
                .noneMatch(m -> m.contains("ordinary word"));
    }

    /** Every getter the runtime hands to {@code resolvePayload} is a position the lint scans. */
    @Test
    void everyGetterTheRuntimeResolvesIsListedAsScanned() throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/io/github/llm4j/loom/execution/HarnessExecutor.java"));
        Matcher m = Pattern.compile("resolvePayload\\((\\w+)\\.(get\\w+)\\(\\)").matcher(source);
        TreeSet<String> used = new TreeSet<>();
        while (m.find()) used.add(m.group(2));
        // the argument of a sub-workflow call is read from a map entry (arg.getValue()), covered
        // below
        assertThat(used).isNotEmpty();
        for (String getter : used)
            assertThat(SubstitutedText.SCANNED_GETTERS)
                    .as("the runtime resolves " + getter + "() but the lint does not scan it")
                    .contains(getter);
    }

    /**
     * A new text-like getter on a statement has to be decided: scanned, or said not to be
     * substituted.
     */
    @Test
    void everyTextGetterOnAStatementIsEitherScannedOrSaidNotToBeSubstituted() throws Exception {
        List<String> undecided = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("target/classes/io/github/llm4j/loom/ast"))) {
            for (Path f :
                    files.filter(p -> p.getFileName().toString().endsWith("Stmt.class")).toList()) {
                Class<?> c =
                        Class.forName(
                                "io.github.llm4j.loom.ast."
                                        + f.getFileName().toString().replace(".class", ""));
                for (Method method : c.getDeclaredMethods()) {
                    if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())
                            || method.getParameterCount() != 0
                            || !method.getName().startsWith("get")) continue;
                    Class<?> t = method.getReturnType();
                    boolean textLike =
                            t == String.class
                                    || t == io.github.llm4j.loom.ast.ValueExpr.class
                                    || java.util.Map.class.isAssignableFrom(t)
                                    || (java.util.List.class.isAssignableFrom(t)
                                            && method.getGenericReturnType()
                                                    .getTypeName()
                                                    .contains("String"))
                                    || method.getGenericReturnType().getTypeName().contains("Arg");
                    if (!textLike) continue;
                    String key = c.getSimpleName() + "." + method.getName();
                    if (!SubstitutedText.SCANNED.contains(key)
                            && !SubstitutedText.NOT_SUBSTITUTED.contains(key)) undecided.add(key);
                }
            }
        }
        assertThat(undecided)
                .as("decide whether the runtime substitutes these (SubstitutedText)")
                .isEmpty();
    }

    @Test
    void underSubstitutionBracesAWordThatMatchesAVariableIsNotReported() {
        LoomScript script = parse("substitution: braces\n" + AGENT + "workflow W(out) {\n  note \"length out of range\"\n}\n");

        assertThat(WorkflowLint.check(script.getWorkflows().get(0), script)).noneMatch(f -> f.message().contains("ordinary word"));
    }

    @Test
    void underTheOlderFormTheSameWordIsStillReported() {
        assertThat(lint("  note \"length out of range\"")).anyMatch(m -> m.contains("ordinary word"));
    }
}
