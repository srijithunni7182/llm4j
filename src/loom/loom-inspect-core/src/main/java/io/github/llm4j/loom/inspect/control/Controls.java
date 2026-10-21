package io.github.llm4j.loom.inspect.control;

import java.util.Set;

/**
 * What the page may ask of a run, and how the ask reaches it: directly in an embedded process, as a
 * queued command through Fabric. The page shows what {@link #allowed} says and nothing else, and
 * every request goes through {@link #request}.
 */
public interface Controls {

    enum Action {
        PAUSE,
        RESUME,
        SUSPEND,
        STOP;

        public String word() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Action of(String word) {
            for (Action a : values()) if (a.word().equals(word)) return a;
            return null;
        }
    }

    /**
     * What happened to a request: taken (it will happen at the run's next step), or refused with a
     * reason.
     */
    record Outcome(boolean accepted, int status, String message) {
        public static Outcome taken(String message) {
            return new Outcome(true, 202, message);
        }

        public static Outcome refused(int status, String message) {
            return new Outcome(false, status, message);
        }
    }

    /**
     * False when controls are switched off here (no admin password is set); {@link #disabledReason}
     * says what to do.
     */
    boolean enabled();

    String disabledReason();

    /**
     * What may be asked of this run now. Empty when it has ended, is unknown, or cannot be reached.
     */
    Set<Action> allowed(String traceId);

    /** Asks for {@code action} on the run, on behalf of {@code who}. */
    Outcome request(String traceId, Action action, String who);
}
