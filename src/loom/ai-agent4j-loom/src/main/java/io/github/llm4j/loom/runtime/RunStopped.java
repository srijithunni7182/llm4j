package io.github.llm4j.loom.runtime;

/** Thrown out of {@code executeWorkflow} when the run was told to stop at a place ({@code --stop-at}) and has got there. Nothing is lost: the run can be resumed. */
public class RunStopped extends RuntimeException {

    private final String point;

    public RunStopped(String point) {
        super("Stopped at " + point);
        this.point = point;
    }

    /** The step or checkpoint the run stopped at. */
    public String point() {
        return point;
    }
}
