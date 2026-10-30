package io.github.llm4j.eval.export;

/**
 * The exception that stops a JUnit test without failing it. Kept out of {@link EvalRun} so that
 * class (also used by the {@code weave eval} jar, which carries no JUnit) links without {@code
 * org.opentest4j}: the JVM loads a class named by a {@code throw} when it verifies the method. The
 * JUnit class is looked up by name, and only when a test is really being stopped.
 */
final class StopTest {

    private StopTest() {}

    /**
     * What to throw to stop the running test with {@code message}: JUnit's abort exception, or an
     * {@link IllegalStateException} with the same message where JUnit is not on the class path
     * (there is no test to stop).
     */
    static RuntimeException because(String message) {
        // by name: a direct `new` would make verifying this class load the JUnit class too
        try {
            return (RuntimeException)
                    Class.forName("org.opentest4j.TestAbortedException")
                            .getConstructor(String.class)
                            .newInstance(message);
        } catch (ReflectiveOperationException | LinkageError e) {
            return new IllegalStateException(message, e);
        }
    }
}
