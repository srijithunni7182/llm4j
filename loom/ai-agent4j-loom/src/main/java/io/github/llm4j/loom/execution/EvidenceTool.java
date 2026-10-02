package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.runtime.RunJournal;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * The tools of an agent that proposes a decision. While it proposes, every read it makes and what came back is written to the run journal (the
 * case's <b>evidence</b>), so a replay of the case can answer the same read the same way. In a replay the wrapper does the opposite: a recorded read
 * is answered from the journal, a pure tool runs, and everything else is simulated (a tool kind added later is stubbed until it says otherwise).
 */
final class EvidenceTool implements Tool {

    private final Tool real;
    private final HarnessExecutor run;

    EvidenceTool(Tool real, HarnessExecutor run) {
        this.real = real;
        this.run = run;
    }

    Tool inner() {
        return real;
    }

    @Override
    public String getName() {
        return real.getName();
    }

    @Override
    public String getDescription() {
        return real.getDescription();
    }

    @Override
    public boolean requiresApproval(Map<String, Object> args) {
        return real.requiresApproval(args);
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        Decider.Proposing proposing = run.decider().proposing();
        if (proposing == null) return real.execute(args);
        Replay replay = run.replay();
        if (replay != null) return replayed(proposing, replay, args);
        boolean read = ReadClass.isRead(real, args);
        String result = real.execute(args);
        if (read) proposing.record(real.getName(), hash(args), result);
        else proposing.effectDuringProposal();
        return result;
    }

    private String replayed(Decider.Proposing proposing, Replay replay, Map<String, Object> args) throws Exception {
        String name = real.getName();
        if (replay.allows(name)) {
            replay.allowedRan(name);
            return ReadClass.runLive(real, args);
        }
        if (ReadClass.isPure(real)) return real.execute(args);
        if (ReadClass.isClock(real)) return replay.caseTime().toString();
        if (ReadClass.isRead(real, args)) {
            String recorded = proposing.recorded(name, hash(args));
            if (recorded != null) return recorded;
            if (replay.liveReads()) {
                replay.live(name);
                return ReadClass.runLive(real, args);
            }
            replay.unrecorded(name);
            throw new Replay.Unreplayable("unrecorded_read", name + " was read with arguments the case had not recorded");
        }
        replay.simulated(name);
        return SimulatingTool.SIMULATED;
    }

    /** A hash of the call's arguments, the same whatever order they were written in. */
    static String hash(Map<String, Object> args) {
        try {
            String canonical = new TreeMap<>(args == null ? Map.of() : args).toString();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void write(RunJournal journal, String step, int n, String tool, String argsHash, String result) {
        journal.put(step + "#decide-evidence:" + n, new RunJournal.Entry("evidence", Map.of("tool", tool, "args", argsHash, "result", result)));
    }
}
