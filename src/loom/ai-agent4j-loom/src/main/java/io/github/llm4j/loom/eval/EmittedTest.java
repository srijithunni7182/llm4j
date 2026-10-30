package io.github.llm4j.loom.eval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The text of the JUnit class {@code weave eval --emit-test} writes. It drives {@code weave eval}
 * itself, so a Java test and the command line can never disagree about what an evaluation does.
 */
public final class EmittedTest {

    private static final String SOURCE_ROOT = "src/test/java";
    private static final long TOKEN_CAP = 200_000;

    private EmittedTest() {}

    /**
     * The class text for {@code file}, evaluating {@code script}.
     *
     * @throws IllegalArgumentException when the file name or a folder of its package is not a Java
     *     name, or the script path has a line break: the class would not compile
     */
    public static String source(Path file, Path script) {
        String name = file.getFileName().toString().replaceFirst("\\.java$", "");
        requireJavaName(name, "the class name (from " + file.getFileName() + ")");
        String pkg = packageOf(file);
        for (String part : pkg.isEmpty() ? new String[0] : pkg.split("\\.")) requireJavaName(part, "the package folder " + part);
        if (script.toString().matches("(?s).*[\\r\\n].*"))
            throw new IllegalArgumentException("the script path has a line break and cannot be written into a Java string");
        return (pkg.isEmpty() ? "" : "package " + pkg + ";\n\n")
                + """
                import static org.assertj.core.api.Assertions.assertThat;
                import static org.junit.jupiter.api.Assumptions.assumeTrue;

                import io.github.llm4j.loom.cli.WeaveCLI;
                import org.junit.jupiter.api.Test;

                /**
                 * Evaluates the workflow with weave eval. The first test is free: it runs the wiring on a
                 * mock model. The second spends money, so it runs only when asked (-Deval.real=true), under
                 * a cap (-Deval.maxCost=<amount> with -Deval.prices=<file>, else a token cap), and prints
                 * the report. A mock run proves wiring, not quality.
                 */
                class %NAME% {

                    private static final String SCRIPT = "%SCRIPT%";

                    @Test
                    void theWiringRunsOnAMockModelAtNoCost() {
                        assertThat(WeaveCLI.run("eval", SCRIPT, "--mock")).isZero();
                    }

                    @Test
                    void aRealEvaluationRunsOnlyWhenAskedAndStaysUnderACap() {
                        assumeTrue(Boolean.getBoolean("eval.real"), "a real evaluation spends money: run with -Deval.real=true");
                        String prices = System.getProperty("eval.prices");
                        String[] cap =
                                prices != null
                                        ? new String[] {"--max-cost", System.getProperty("eval.maxCost", "0.50"), "--prices", prices}
                                        : new String[] {"--max-tokens", System.getProperty("eval.maxTokens", "%TOKENS%")};
                        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of("eval", SCRIPT, "--yes"));
                        args.addAll(java.util.List.of(cap));
                        assertThat(WeaveCLI.run(args.toArray(String[]::new))).as("weave eval exit code (1: a case failed, 2: a problem)").isZero();
                    }
                }
                """
                        .replace("%NAME%", name)
                        .replace("%SCRIPT%", script.toString().replace("\\", "/").replace("\"", "\\\""))
                        .replace("%TOKENS%", Long.toString(TOKEN_CAP));
    }

    private static void requireJavaName(String name, String what) {
        boolean valid = !name.isEmpty() && Character.isJavaIdentifierStart(name.codePointAt(0)) && name.codePoints().allMatch(Character::isJavaIdentifierPart);
        if (!valid)
            throw new IllegalArgumentException(what + " is not a Java name; use letters, digits and underscores, starting with a letter");
    }

    private static String packageOf(Path file) {
        String normal = file.toString().replace('\\', '/');
        int at = normal.lastIndexOf(SOURCE_ROOT + "/");
        if (at < 0) return "";
        String below = normal.substring(at + SOURCE_ROOT.length() + 1);
        int slash = below.lastIndexOf('/');
        if (slash < 0) return "";
        List<String> parts = new ArrayList<>(List.of(below.substring(0, slash).split("/")));
        return String.join(".", parts);
    }
}
