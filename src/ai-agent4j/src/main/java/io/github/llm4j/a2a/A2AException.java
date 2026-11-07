package io.github.llm4j.a2a;

import io.github.llm4j.exception.LLMException;

/**
 * Something went wrong with an A2A exchange. Subclasses name what the protocol reports, so callers
 * never see a wire code. Messages say what to do about it.
 */
public class A2AException extends LLMException {

    /**
     * Creates the exception.
     *
     * @param message what went wrong and what to do
     */
    public A2AException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message what went wrong and what to do
     * @param cause the cause
     */
    public A2AException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The remote has no such task (or the caller may not see it). */
    public static final class TaskNotFound extends A2AException {
        /**
         * @param taskId the id that was asked for
         */
        public TaskNotFound(String taskId) {
            super("no task " + taskId + " on the remote agent; check the id, or start a new send");
        }
    }

    /** The task cannot be canceled in its current state. */
    public static final class TaskNotCancelable extends A2AException {
        /**
         * @param taskId the task
         */
        public TaskNotCancelable(String taskId) {
            super("task " + taskId + " cannot be canceled in its current state");
        }
    }

    /** The remote does not support the operation. */
    public static final class UnsupportedOperation extends A2AException {
        /**
         * @param operation the operation
         */
        public UnsupportedOperation(String operation) {
            super("the remote agent does not support " + operation);
        }
    }

    /** The remote does not support a content type that was sent. */
    public static final class ContentTypeNotSupported extends A2AException {
        /**
         * @param detail what was refused
         */
        public ContentTypeNotSupported(String detail) {
            super("the remote agent does not accept this content type: " + detail);
        }
    }

    /** The remote does not speak the protocol version the transport sent. */
    public static final class VersionNotSupported extends A2AException {
        /**
         * @param detail the version and what is supported
         */
        public VersionNotSupported(String detail) {
            super("the remote agent does not support this protocol version: " + detail);
        }
    }

    /** The remote requires an extension the caller did not declare. */
    public static final class ExtensionSupportRequired extends A2AException {
        /**
         * @param extensionUri the extension
         */
        public ExtensionSupportRequired(String extensionUri) {
            super(
                    "the remote agent requires the extension "
                            + extensionUri
                            + ", which this client does not support");
        }
    }

    /**
     * A call was refused by llm4j before anything was sent (a card pointing elsewhere, an
     * unsupported version, a required extension, a file type the card does not accept). Retrying
     * cannot change the outcome.
     */
    public static final class Refused extends A2AException {
        /**
         * @param message what was refused and what to do
         */
        public Refused(String message) {
            super(message);
        }
    }

    /** A response could not be understood. */
    public static final class Malformed extends A2AException {
        /**
         * @param message what was wrong
         * @param cause the parse error, or null
         */
        public Malformed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A consumer's channel broke its contract or a bound was exceeded. */
    public static final class ChannelFailure extends A2AException {
        /**
         * @param message what happened
         */
        public ChannelFailure(String message) {
            super(message);
        }

        /**
         * @param message what happened
         * @param cause the channel's exception
         */
        public ChannelFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
