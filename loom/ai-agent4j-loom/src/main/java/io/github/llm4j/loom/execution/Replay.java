package io.github.llm4j.loom.execution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Makes one run a replay of a past case under a candidate: nobody is asked, the ledger and the levels are not touched, and the decision's agent
 * reads from what the case recorded. What happened is collected here for the report.
 */
public final class Replay {

    private final boolean liveReads;
    private final boolean noMemory;
    private final Set<String> allowed;
    private final Instant caseTime;

    private final List<String> simulated = new ArrayList<>();
    private final Set<String> unrecorded = new LinkedHashSet<>();
    private final Set<String> live = new LinkedHashSet<>();
    private final Set<String> allowedRun = new LinkedHashSet<>();
    private boolean proposedAfterSimulation;

    /**
     * @param liveReads reads that were not recorded run live (flagged non-deterministic) instead of making the case unreplayable
     * @param noMemory  memory and knowledge reads are not run
     * @param allowed   tools declared {@code replay: allow}
     * @param caseTime  what the clock tool answers: when the case was decided
     */
    public Replay(boolean liveReads, boolean noMemory, Set<String> allowed, Instant caseTime) {
        this.liveReads = liveReads;
        this.noMemory = noMemory;
        this.allowed = allowed;
        this.caseTime = caseTime;
    }

    public boolean liveReads() { return liveReads; }
    public boolean noMemory() { return noMemory; }
    public boolean allows(String tool) { return allowed.contains(tool); }
    public Instant caseTime() { return caseTime; }

    synchronized void simulated(String tool) {
        simulated.add(tool);
        proposedAfterSimulation = true;
    }

    synchronized void unrecorded(String tool) { unrecorded.add(tool); }
    synchronized void live(String tool) { live.add(tool); }
    synchronized void allowedRan(String tool) { allowedRun.add(tool); }

    /** The tools that were simulated while proposing. */
    public synchronized List<String> simulatedCalls() { return List.copyOf(simulated); }
    /** Reads the candidate made that the case had not recorded. */
    public synchronized Set<String> unrecordedReads() { return Set.copyOf(unrecorded); }
    /** Reads that ran live. */
    public synchronized Set<String> liveReadsUsed() { return Set.copyOf(live); }
    public synchronized Set<String> allowedToolsRun() { return Set.copyOf(allowedRun); }
    /** The proposal was made after a tool was only simulated, so on less information than the original. */
    public synchronized boolean proposedAfterSimulation() { return proposedAfterSimulation; }

    /** Thrown when the candidate reads something the case did not record: the case cannot be replayed under this candidate. */
    public static final class Unreplayable extends RuntimeException {
        private final String reason;

        public Unreplayable(String reason, String message) {
            super(message, null, false, false);
            this.reason = reason;
        }

        public String reason() { return reason; }
    }
}
