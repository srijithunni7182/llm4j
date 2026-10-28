package io.github.llm4j.loom.runtime;

/**
 * A question for a person that nobody can answer: the console it would be asked on is closed (a
 * script, a pipe, a scheduler). It is not a failure of the workflow's own logic, so the run says so
 * plainly and names the ways to let a run wait for an answer instead.
 */
public final class NoHumanAvailable extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final String question;

    public NoHumanAvailable(String question) {
        super("a person had to answer, but standard input is closed");
        this.question = question == null ? "" : question;
    }

    /** What was asked. */
    public String question() {
        return question;
    }
}
