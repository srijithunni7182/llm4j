package io.github.llm4j.agent;

import java.util.List;
import java.util.Objects;

/**
 * Checks at the three places in a run that {@link ToolHook} does not reach: when the question
 * arrives, before each model call, and before the agent settles on its final answer. Register one
 * with {@link ReActAgent.Builder#addRunHook}; hooks run in the order they were added.
 *
 * <ul>
 *   <li>{@link #onInput}: sees the question once, before anything else. It can let it through,
 *       {@link Input#replace replace} it (redact personal data, add context) or {@link Input#reject
 *       reject} it, which ends the run at once without a model call.
 *   <li>{@link #beforeModelCall}: sees where the run is before each model call and can add a {@link
 *       ModelCall#withNote note} that the model reads in that one call only (a reminder, "you have
 *       3 steps left"). The note is not kept in the conversation.
 *   <li>{@link #beforeSettle}: sees the answer the model gave when it is about to finish. It can
 *       accept it, {@link Settle#replace replace} it or {@link Settle#sendBack send it back} with a
 *       message the model reads and can act on ("the tests have not been run"). An agent is sent
 *       back at most {@link ReActAgent.Builder#maxSettleRetries} times in a run, so a hook can
 *       never keep it from finishing. A wrap-up asked for through a {@link RunHandle}, and a run
 *       that ends because its budget ran out, are not sent back.
 * </ul>
 *
 * <p>A hook that throws is a hook that failed to check, so it fails closed: a throwing {@code
 * onInput} rejects the question; a throwing {@code beforeModelCall} ends the run with the
 * exception, before the model is called; a throwing {@code beforeSettle} never lets the answer
 * through: it counts as an error in that step, the model is told and may try again, and the run
 * does not complete if the hook keeps failing. Hooks run on the thread that runs the agent.
 */
public interface RunHook {

    /** Called once with the question. The default lets it through. */
    default Input onInput(String question) {
        return Input.allow();
    }

    /** Called before each model call. The default adds nothing. */
    default ModelCall beforeModelCall(CallView view) {
        return ModelCall.proceed();
    }

    /** Called with the answer the model gave when it is about to finish. The default accepts it. */
    default Settle beforeSettle(String answer, List<AgentResult.AgentStep> steps) {
        return Settle.accept();
    }

    /**
     * Where the run is at a model call: the model call number (from 0), the most steps it may take,
     * and what it has done so far.
     */
    record CallView(int iteration, int maxIterations, List<AgentResult.AgentStep> steps) {
        public CallView {
            steps = List.copyOf(steps);
        }

        /** Steps left, counting this one. */
        public int remaining() {
            return Math.max(0, maxIterations - iteration);
        }
    }

    /** What a hook decided about the question. */
    final class Input {
        enum Kind {
            ALLOW,
            REPLACE,
            REJECT
        }

        private static final Input ALLOW = new Input(Kind.ALLOW, null);
        private final Kind kind;
        private final String text;

        private Input(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        public static Input allow() {
            return ALLOW;
        }

        /** Use this text as the question from here on. */
        public static Input replace(String question) {
            return new Input(
                    Kind.REPLACE, Objects.requireNonNull(question, "question cannot be null"));
        }

        /**
         * Do not run. The message becomes the run's result, so say why the question was refused.
         */
        public static Input reject(String message) {
            return new Input(
                    Kind.REJECT, Objects.requireNonNull(message, "message cannot be null"));
        }

        Kind kind() {
            return kind;
        }

        String text() {
            return text;
        }
    }

    /** What a hook decided about a model call. */
    final class ModelCall {
        private static final ModelCall PROCEED = new ModelCall(null);
        private final String note;

        private ModelCall(String note) {
            this.note = note;
        }

        public static ModelCall proceed() {
            return PROCEED;
        }

        /** Show the model this note in this call only. */
        public static ModelCall withNote(String note) {
            return new ModelCall(Objects.requireNonNull(note, "note cannot be null"));
        }

        String note() {
            return note;
        }
    }

    /** What a hook decided about a final answer. */
    final class Settle {
        enum Kind {
            ACCEPT,
            REPLACE,
            SEND_BACK
        }

        private static final Settle ACCEPT = new Settle(Kind.ACCEPT, null);
        private final Kind kind;
        private final String text;

        private Settle(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        public static Settle accept() {
            return ACCEPT;
        }

        /** Finish with this answer instead. */
        public static Settle replace(String answer) {
            return new Settle(
                    Kind.REPLACE, Objects.requireNonNull(answer, "answer cannot be null"));
        }

        /** Do not accept the answer; the model reads this message and carries on. */
        public static Settle sendBack(String message) {
            return new Settle(
                    Kind.SEND_BACK, Objects.requireNonNull(message, "message cannot be null"));
        }

        Kind kind() {
            return kind;
        }

        String text() {
            return text;
        }
    }
}
