package io.github.llm4j.loom.execution;

/**
 * A step's attachments could not be loaded: no media folder, a path that leaves it, a missing or
 * unsuitable file, too much of it. The step fails without a model call, and is not retried because
 * a retry would meet the same file.
 */
final class MediaRefusal extends RuntimeException {

    MediaRefusal(String message) {
        super(message);
    }

    MediaRefusal(String message, Throwable cause) {
        super(message, cause);
    }
}
