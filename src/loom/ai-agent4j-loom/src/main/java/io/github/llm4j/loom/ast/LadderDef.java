package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * A named, reusable policy for earning autonomy: rungs from the entry rung up, each with a mode and
 * the conditions to climb into it and slip out of it.
 */
public class LadderDef implements Node {
    private final String name;
    private final int line;
    private final List<String> params = new ArrayList<>();
    private final List<RungDef> rungs = new ArrayList<>();
    private String approver;
    private boolean automatic;
    private boolean climbingStated;
    private boolean synthetic;

    public LadderDef(String name, int line) {
        this.name = name;
        this.line = line;
    }

    public String getName() {
        return name;
    }

    public int getLine() {
        return line;
    }

    /** The parameters a binding must supply ({@code ladder BlogLadder(baseline_views)}). */
    public List<String> getParams() {
        return params;
    }

    /** The rungs in ascending order; the first is where every new scope starts. */
    public List<RungDef> getRungs() {
        return rungs;
    }

    public String getApprover() {
        return approver;
    }

    public boolean isAutomatic() {
        return automatic;
    }

    /** True when the ladder said how climbing is approved. */
    public boolean isClimbingStated() {
        return climbingStated;
    }

    public void setApprover(String approver) {
        this.approver = approver;
        this.automatic = false;
        this.climbingStated = true;
    }

    public void setAutomatic() {
        this.approver = null;
        this.automatic = true;
        this.climbingStated = true;
    }

    /**
     * True for a ladder the runtime wrote from a decision's {@code trust { }} block: it is shown by
     * {@code describe} but never checked as if someone wrote it.
     */
    public boolean isSynthetic() {
        return synthetic;
    }

    public void setSynthetic(boolean synthetic) {
        this.synthetic = synthetic;
    }

    public RungDef rung(String name) {
        for (RungDef r : rungs) if (r.getName().equals(name)) return r;
        return null;
    }

    public int indexOf(String rungName) {
        for (int i = 0; i < rungs.size(); i++)
            if (rungs.get(i).getName().equals(rungName)) return i;
        return -1;
    }
}
