package io.github.llm4j.eval.export;

/** Where an evaluation's verdict came from. */
public enum Source {
    /** Evaluated in this run. */
    FRESH,
    /** Evaluated in this run, but the verdict came from the judge cache (nothing changed). */
    REUSED,
    /** Not evaluated in this run; the latest known result from an earlier run. */
    CARRIED
}
