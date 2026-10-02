package io.github.llm4j.loom.autonomy;

/** Lets the command-line tests use the small ladders of {@link Scripts2}. */
public final class Scripts2Access {

    private Scripts2Access() { }

    public static String refund(String trustExtra) {
        return Scripts2.refund(trustExtra);
    }
}
