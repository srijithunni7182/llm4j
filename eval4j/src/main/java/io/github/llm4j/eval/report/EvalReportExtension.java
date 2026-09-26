package io.github.llm4j.eval.report;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * A JUnit 5 extension that prints a pass/fail summary for a test class evaluating agents, without
 * requiring a separate "runner" call. Apply it with {@code @ExtendWith(EvalReportExtension.class)}
 * — it transparently observes whichever assertions (eval4j's or plain JUnit/AssertJ ones) each
 * {@code @Test}/{@code @ParameterizedTest} throws, and prints a summary table in {@code afterAll}.
 *
 * <p>This deliberately depends only on the JUnit 5 extension API, not on any eval4j assertion type:
 * it works the same whether a test failed via an {@link
 * io.github.llm4j.eval.judge.LlmJudgeCondition}, a plain {@code assertEquals}, or anything else.
 */
public class EvalReportExtension implements TestWatcher, AfterAllCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(EvalReportExtension.class);

    @Override
    public void testSuccessful(ExtensionContext context) {
        outcomesFor(context).add(new Outcome(context.getDisplayName(), true, null));
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        outcomesFor(context).add(new Outcome(context.getDisplayName(), false, cause.getMessage()));
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        outcomesFor(context)
                .add(
                        new Outcome(
                                context.getDisplayName(),
                                false,
                                "aborted: " + (cause == null ? "" : cause.getMessage())));
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        // Disabled tests were never evaluated, so they don't belong in a pass/fail eval report.
    }

    @Override
    public void afterAll(ExtensionContext context) {
        List<Outcome> outcomes = outcomesFor(context);
        printReport(context.getDisplayName(), outcomes);
    }

    @SuppressWarnings("unchecked")
    private List<Outcome> outcomesFor(ExtensionContext context) {
        String key = context.getRequiredTestClass().getName();
        ExtensionContext.Store store = context.getRoot().getStore(NAMESPACE);
        return (List<Outcome>)
                store.getOrComputeIfAbsent(key, k -> new CopyOnWriteArrayList<Outcome>());
    }

    private void printReport(String className, List<Outcome> outcomes) {
        long passed = outcomes.stream().filter(Outcome::passed).count();
        System.out.println();
        System.out.println("=== eval4j report: " + className + " ===");
        for (Outcome outcome : outcomes) {
            if (outcome.passed()) {
                System.out.printf("[PASS] %s%n", outcome.testName());
            } else {
                System.out.printf("[FAIL] %s - %s%n", outcome.testName(), outcome.failureMessage());
            }
        }
        System.out.printf("%d/%d passed%n%n", passed, outcomes.size());
    }

    private record Outcome(String testName, boolean passed, String failureMessage) {}
}
