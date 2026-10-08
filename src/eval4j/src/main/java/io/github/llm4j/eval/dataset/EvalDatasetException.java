package io.github.llm4j.eval.dataset;

/** Thrown when a golden dataset file cannot be found, read, or parsed. */
public class EvalDatasetException extends RuntimeException {

    public EvalDatasetException(String message) {
        super(message);
    }

    public EvalDatasetException(String message, Throwable cause) {
        super(message, cause);
    }
}
