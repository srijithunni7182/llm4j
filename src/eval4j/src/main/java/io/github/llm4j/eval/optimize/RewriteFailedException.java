package io.github.llm4j.eval.optimize;

/** The rewriter could not produce a usable proposal (call failed or output unparseable twice). */
final class RewriteFailedException extends RuntimeException {

    RewriteFailedException(String message) {
        super(message);
    }

    RewriteFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
