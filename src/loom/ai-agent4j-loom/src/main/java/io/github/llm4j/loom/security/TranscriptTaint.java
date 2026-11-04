package io.github.llm4j.loom.security;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.CallStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.TranscriptMode;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which agents receive text that an untrusted-content reader put in the shared transcript
 * (docs/specs/24-stateless-nodes, R11). The audit's trifecta counts tools; this counts the other
 * channel. An agent that receives the whole transcript ({@code transcript: all}, the default) at a
 * point where an agent that reads untrusted content has already run is itself exposed to that
 * content, whatever its prompt says. It fails closed: an {@code alt} counts a reader in either
 * branch, a parallel round counts as sequential, and a loop body is walked twice so a reader at its
 * end taints the start on the next round. An internal node is never an agent, so is never tainted,
 * and an extractor between two agents does not remove the first one's words from the transcript.
 */
final class TranscriptTaint {

    /** An agent that receives the transcript, and the first reader whose words it may carry. */
    record Taint(String receiver, String reader, String workflow, int line) {}

    private final Map<String, AgentDef> agents = new LinkedHashMap<>();
    private final Map<String, WorkflowDef> workflows = new LinkedHashMap<>();
    private final Predicate<String> readsUntrusted;
    private final Map<String, Taint> found = new LinkedHashMap<>();
    private String current;

    private TranscriptTaint(LoomScript script, Predicate<String> readsUntrusted) {
        script.getAgents().forEach(a -> agents.put(a.getName(), a));
        script.getWorkflows().forEach(w -> workflows.put(w.getName(), w));
        this.readsUntrusted = readsUntrusted;
    }

    /**
     * Every agent that can receive an untrusted reader's words through the transcript, once each,
     * with the first path found.
     *
     * @param readsUntrusted whether the named agent reads untrusted content itself
     */
    static List<Taint> compute(LoomScript script, Predicate<String> readsUntrusted) {
        TranscriptTaint walk = new TranscriptTaint(script, readsUntrusted);
        for (WorkflowDef w : script.getWorkflows()) {
            walk.current = w.getName();
            walk.block(w.getStatements(), new LinkedHashSet<>(), new LinkedHashSet<>());
        }
        return new ArrayList<>(walk.found.values());
    }

    /** Walks statements in order; {@code readers} is what has run so far, in order. */
    private void block(List<Statement> statements, Set<String> readers, Set<String> inCalls) {
        if (statements == null) return;
        for (Statement s : statements) statement(s, readers, inCalls);
    }

    private void statement(Statement s, Set<String> readers, Set<String> inCalls) {
        if (s instanceof DelegateStmt d) {
            if (!d.hasDynamicTarget()) run(d.getTargetAgent(), d.getLine(), readers);
        } else if (s instanceof HandoffStmt h) {
            run(h.getTargetAgent(), h.getLine(), readers);
        } else if (s instanceof BroadcastStmt b) {
            for (String target : b.getTargetAgents()) run(target, b.getLine(), readers);
        } else if (s instanceof CallStmt c) {
            WorkflowDef called = workflows.get(c.getWorkflowName());
            if (called != null && inCalls.add(called.getName())) {
                String outer = current;
                block(called.getStatements(), readers, inCalls);
                current = outer;
                inCalls.remove(called.getName());
            }
        } else if (s instanceof AltStmt a) {
            Set<String> ifSide = new LinkedHashSet<>(readers);
            block(a.getIfBranch(), ifSide, inCalls);
            Set<String> elseSide = new LinkedHashSet<>(readers);
            block(a.getElseBranch(), elseSide, inCalls);
            readers.addAll(ifSide);
            readers.addAll(elseSide);
        } else if (s instanceof LoopStmt l) {
            block(l.getBody(), readers, inCalls);
            block(l.getBody(), readers, inCalls); // a reader at the end taints the start next round
            block(l.getOnExhausted(), readers, inCalls);
        } else if (s instanceof ForEachStmt f) {
            block(f.getBody(), readers, inCalls);
            block(f.getBody(), readers, inCalls);
            block(f.getOnExhausted(), readers, inCalls);
        } else {
            for (List<Statement> nested : StatementWalker.nested(s)) block(nested, readers, inCalls);
        }
    }

    /** An agent runs: it may carry what the readers before it said, then it may be a reader. */
    private void run(String name, int line, Set<String> readers) {
        AgentDef agent = agents.get(name);
        if (agent == null) return;
        if (agent.getTranscriptMode() == TranscriptMode.ALL && !readsUntrusted.test(name)) {
            for (String reader : readers) {
                if (!reader.equals(name)) {
                    found.putIfAbsent(name, new Taint(name, reader, current, line));
                    break;
                }
            }
        }
        if (readsUntrusted.test(name)) readers.add(name);
    }
}
