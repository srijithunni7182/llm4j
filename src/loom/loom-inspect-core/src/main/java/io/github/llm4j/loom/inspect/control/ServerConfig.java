package io.github.llm4j.loom.inspect.control;

import java.util.List;
import java.util.Map;

/**
 * Settings an administrator keeps at a server and edits from its page: today the inspect rules and
 * the price table. Each is a text document in the same format as the file a process keeps beside
 * its script, so what is learnt in one place carries to the other.
 */
public interface ServerConfig {

    /**
     * What happened to a save: kept, or refused with the problems (line numbers and reasons) found.
     */
    record Result(boolean saved, int status, String message, List<String> problems) {
        public static Result saved(String message, List<String> warnings) {
            return new Result(true, 200, message, warnings);
        }

        public static Result refused(int status, String message, List<String> problems) {
            return new Result(false, status, message, problems);
        }
    }

    /**
     * Each section by name ({@code rules}, {@code prices}): its text, a one-line summary of what it
     * does now, and any warnings.
     */
    Map<String, Map<String, Object>> view();

    /**
     * The choices the page may offer, from what is known of the runs: {@code agents} and {@code
     * models}.
     */
    default Map<String, Object> catalog() {
        return Map.of();
    }

    /**
     * As {@link #save}, from the page's structured editor: {@code json} is the section's structure
     * (see {@link #view}'s {@code structured}).
     */
    default Result saveStructured(String section, String json, String who) {
        return Result.refused(400, "this section cannot be edited that way", List.of());
    }

    /**
     * Checks and keeps the text of {@code section}, on behalf of {@code who}. Nothing is kept
     * unless it is whole and valid.
     */
    Result save(String section, String text, String who);
}
