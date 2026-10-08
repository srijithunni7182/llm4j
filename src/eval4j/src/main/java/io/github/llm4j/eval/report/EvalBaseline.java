package io.github.llm4j.eval.report;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Turns a test class that applies {@link EvalReportExtension} into a regression gate: after all its
 * tests run, each judged metric's score is compared with a checked-in baseline file and the class
 * fails if any metric dropped by more than {@link #maxRegression()}.
 *
 * <p>Create or refresh the baseline by running with {@code -Deval4j.baseline.update=true}; it is
 * never written otherwise. Because judge scores are noisy, the default {@link Granularity#SUITE}
 * compares per-metric <em>averages across the class</em> rather than individual cases.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EvalBaseline {

    /** Baseline file, relative to the working directory. */
    String file() default "eval4j-baseline.json";

    /** Largest tolerated absolute score drop (inclusive) before the gate fails. */
    double maxRegression() default 0.05;

    Granularity granularity() default Granularity.SUITE;

    enum Granularity {
        /** Compare the average score of each metric across the whole test class. */
        SUITE,
        /** Compare each (test, metric) individually. Noisier. */
        CASE
    }
}
