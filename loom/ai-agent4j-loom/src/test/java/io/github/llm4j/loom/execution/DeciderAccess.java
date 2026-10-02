package io.github.llm4j.loom.execution;

/** Reaches the package-private parts of the decision runner that tests check on their own. */
public final class DeciderAccess {

    private DeciderAccess() { }

    public static boolean sampled(String caseId, String decision, double percent) {
        return Decider.sampledForAudit(caseId, decision, percent);
    }
}
