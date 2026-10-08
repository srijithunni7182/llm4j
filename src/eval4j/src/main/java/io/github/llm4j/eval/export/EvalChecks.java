package io.github.llm4j.eval.export;

import java.util.function.Supplier;

/**
 * Names and classifies the deterministic assertions inside a scope:
 *
 * <pre>{@code
 * EvalChecks.named("refund-tool-order").dimension("reasoning").facet("tools")
 *         .run(() -> assertThat(result).usesToolsInOrder("order_lookup", "refund"));
 * }</pre>
 *
 * Assertions inside the scope are recorded under that metric id instead of their automatic default.
 * The scope does not change behaviour: a failed assertion still throws the same {@link
 * AssertionError}.
 */
public final class EvalChecks {

    private final MetricRef metric;

    private EvalChecks(MetricRef metric) {
        this.metric = metric;
    }

    public static EvalChecks named(String id) {
        return new EvalChecks(MetricRef.assertion(MetricRef.slug(id), id, "agents", "tools", null));
    }

    public EvalChecks dimension(String dimension) {
        return new EvalChecks(metric.dimension(dimension));
    }

    public EvalChecks family(String family) {
        return new EvalChecks(metric.family(family));
    }

    public EvalChecks facet(String facet) {
        return new EvalChecks(metric.facet(facet));
    }

    /** Runs {@code body} with this metric as the override for assertions made inside it. */
    public void run(Runnable body) {
        EvalRun run = EvalRun.get();
        EvalRun.MetricOverride previous = run.override();
        run.setOverride(new EvalRun.MetricOverride(metric));
        try {
            body.run();
        } finally {
            if (previous == null) {
                run.clearOverride();
            } else {
                run.setOverride(previous);
            }
        }
    }

    public <T> T call(Supplier<T> body) {
        Object[] box = new Object[1];
        run(() -> box[0] = body.get());
        @SuppressWarnings("unchecked")
        T t = (T) box[0];
        return t;
    }

    /**
     * Runs {@code check}, recording a pass when it returns and a failure when it throws {@link
     * AssertionError} (which is rethrown unchanged). The metric is the scope's override when one is
     * active, otherwise {@code defaults}.
     */
    public static void check(MetricRef defaults, Runnable check) {
        checkMeasured(defaults, null, null, null, check);
    }

    /** Like {@link #check} for a measured value compared with a budget. */
    public static void checkMeasured(
            MetricRef defaults, Double value, String unit, Double budget, Runnable check) {
        EvalRun run = EvalRun.get();
        EvalRun.MetricOverride o = run.override();
        MetricRef metric = o != null ? o.metric() : defaults;
        if (!run.isExporting()) {
            check.run();
            return;
        }
        // Nested assertions (an assertion implemented via another) are recorded once, outermost.
        if (NESTING.get() > 0) {
            check.run();
            return;
        }
        NESTING.set(NESTING.get() + 1);
        try {
            check.run();
            Evaluation.Builder b = Evaluation.builder(metric).score(1.0).passed(true);
            if (value != null) {
                b.measured(value, unit, budget).display(value + (unit == null ? "" : " " + unit));
            }
            run.record(b);
        } catch (AssertionError e) {
            Evaluation.Builder b =
                    Evaluation.builder(metric)
                            .score(0.0)
                            .passed(false)
                            .reason(trim(e.getMessage()));
            if (value != null) {
                b.measured(value, unit, budget).display(value + (unit == null ? "" : " " + unit));
            }
            run.record(b);
            throw e;
        } finally {
            NESTING.set(NESTING.get() - 1);
        }
    }

    private static final ThreadLocal<Integer> NESTING = ThreadLocal.withInitial(() -> 0);

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 2000 ? s.substring(0, 2000) + "…" : s;
    }
}
