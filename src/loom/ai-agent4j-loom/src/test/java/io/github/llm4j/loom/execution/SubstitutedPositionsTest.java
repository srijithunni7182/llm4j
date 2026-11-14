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

}
