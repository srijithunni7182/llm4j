package io.github.llm4j.eval.judge;

/**
 * Thrown when a judge {@link io.github.llm4j.LLMClient} call fails or its response cannot be
 * interpreted as a verdict. This is distinct from a failed evaluation: a low score that is below
 * threshold is a normal {@link AssertionError} raised by AssertJ; this exception means the judge
 * itself could not be consulted.
 */
public class JudgeEvaluationException extends RuntimeException {

    public JudgeEvaluationException(String message) {
        super(message);
    }

    public JudgeEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
