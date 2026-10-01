package io.github.llm4j.eval.optimize;

/** Observes an optimization run. All methods are optional. */
public interface OptimizerListener {

    /** Called after each round is recorded (and checkpointed). */
    default void onRound(Round round) {}

    /** Called for configuration and runtime warnings. */
    default void onWarning(String message) {}

    /** Called once when the loop stops, before confirmation and test scoring. */
    default void onStop(StopReason reason) {}

    /** A listener that prints one line per round to standard output. */
    static OptimizerListener console() {
        return new OptimizerListener() {
            @Override
            public void onRound(Round r) {
                System.out.printf(
                        java.util.Locale.ROOT,
                        "[optimizer] round %d: %s (parent %s, param %s, batch %.2f -> %s%s)%n",
                        r.index(),
                        r.action(),
                        r.parentId(),
                        r.parameter(),
                        r.parentBatchMean(),
                        r.childBatchMean() == null
                                ? "n/a"
                                : String.format(java.util.Locale.ROOT, "%.2f", r.childBatchMean()),
                        r.validationMean() == null
                                ? ""
                                : String.format(
                                        java.util.Locale.ROOT,
                                        ", validation %.2f",
                                        r.validationMean()));
            }

            @Override
            public void onWarning(String message) {
                System.out.println("[optimizer] WARNING: " + message);
            }

            @Override
            public void onStop(StopReason reason) {
                System.out.println("[optimizer] stopped: " + reason);
            }
        };
    }
}
