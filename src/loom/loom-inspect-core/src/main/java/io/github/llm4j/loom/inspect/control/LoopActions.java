package io.github.llm4j.loom.inspect.control;

import java.util.Map;

/**
 * What the page may do with a finished or running run's journal: turn it into a test case and try a
 * change on it. The server checks who is asking and records the ask; the implementation reads the
 * journal where it lives and says plainly when it cannot.
 */
public interface LoopActions {

    /**
     * Whether the capture permission is on here. Capture exposes run content, so it is off until it
     * is configured, whoever is signed in.
     */
    boolean enabled();

    /** Why it is off, for the page. */
    String disabledReason();

    /**
     * What came of an action. When the journal is not readable here, {@code command} is the command
     * to run where the store is.
     */
    record Outcome(
            boolean ok,
            int status,
            String message,
            String name,
            String text,
            String savedTo,
            String command) {
        public static Outcome failed(int status, String message) {
            return new Outcome(false, status, message, null, null, null, null);
        }

        public static Outcome elsewhere(String message, String command) {
            return new Outcome(false, 200, message, null, null, null, command);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("ok", ok);
            m.put("message", message);
            if (name != null) m.put("name", name);
            if (text != null) m.put("text", text);
            if (savedTo != null) m.put("savedTo", savedTo);
            if (command != null) {
                m.put("fallback", true);
                m.put("command", command);
            }
            return m;
        }
    }

    /**
     * Takes a test case from a run.
     *
     * @param kind the incident kind it came from, or null
     * @param injection true when the run's content was flagged as a suspected injection
     */
    Outcome capture(String traceId, String kind, boolean injection, String who);

    /**
     * Replays a run with a change, from recorded answers only. {@code report} is in {@code text}.
     */
    Outcome tryChange(
            String traceId,
            String at,
            Map<String, String> sets,
            Map<String, String> answers,
            String who);

    /** How many tests were created from this kind of incident. */
    int testsFor(String kind);
}
