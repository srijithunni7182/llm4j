package io.github.llm4j.agent;

import io.github.llm4j.telemetry.ErrorCategory;
import java.util.Map;
import java.util.Objects;

/**
 * A check on every tool call, in plain Java. Register one with {@link
 * ReActAgent.Builder#addToolHook}; hooks run in the order they were added.
 *
 * <p>{@link #beforeCall} sees the arguments the model wrote, after they are parsed and before a
 * person is asked to approve the call and before the tool runs. It can let the call through, {@link
 * Decision#reject reject} it with a message the model reads and can act on (the usual answer to an
 * argument that is wrong), or {@link Decision#replaceArguments replace} the arguments (to normalise
 * a value or fill in what the model cannot know). A person who is asked to approve a call sees the
 * arguments as the hooks left them. {@link #afterCall} sees the result and returns what the model
 * will be shown, for example with secrets removed; the audit log records that version.
 *
 * <p>A hook that throws is a hook that failed to check, so the call does not run (and the result is
 * withheld) rather than going through unchecked. Hooks run on the thread that runs the agent.
 */
public interface ToolHook {

    /** Called before the tool runs. The default lets everything through. */
    default Decision beforeCall(String toolName, Map<String, Object> arguments) {
        return Decision.allow();
    }

    /**
     * Called with the tool's result; returns what the model is shown. The default returns it
     * unchanged.
     */
    default String afterCall(String toolName, Map<String, Object> arguments, String result) {
        return result;
    }

    /** What a hook decided about a call. */
    final class Decision {
        enum Kind {
            ALLOW,
            REJECT,
            REPLACE
        }

        private static final Decision ALLOW = new Decision(Kind.ALLOW, null, null, null);

        private final Kind kind;
        private final String message;
        private final Map<String, Object> arguments;
        private final ErrorCategory category;

        private Decision(
                Kind kind, String message, Map<String, Object> arguments, ErrorCategory category) {
            this.kind = kind;
            this.message = message;
            this.arguments = arguments;
            this.category = category;
        }

        /** Let the call through as it is. */
        public static Decision allow() {
            return ALLOW;
        }

        /**
         * Do not run the call. The message is shown to the model as the result, so say what is
         * wrong and how to put it right ("limit must be between 1 and 100; you sent 5000").
         */
        public static Decision reject(String message) {
            return reject(message, ErrorCategory.INVALID_ARGUMENTS);
        }

        /**
         * As {@link #reject(String)}, recorded in the run's statistics under the given category
         * (for a refusal that is not about the arguments).
         */
        public static Decision reject(String message, ErrorCategory category) {
            Objects.requireNonNull(message, "message cannot be null");
            return new Decision(
                    Kind.REJECT, message, null, category == null ? ErrorCategory.OTHER : category);
        }

        /**
         * Run the call with these arguments instead. Later hooks and the tool see them, and so does
         * a person asked to approve the call.
         */
        public static Decision replaceArguments(Map<String, Object> arguments) {
            Objects.requireNonNull(arguments, "arguments cannot be null");
            return new Decision(Kind.REPLACE, null, Map.copyOf(arguments), null);
        }

        Kind kind() {
            return kind;
        }

        String message() {
            return message;
        }

        Map<String, Object> arguments() {
            return arguments;
        }

        ErrorCategory category() {
            return category;
        }
    }
}
