package io.github.llm4j.loom.autonomy;

import java.time.Instant;
import java.util.Map;

/** Cases written straight into a ledger, so a ladder can be judged without running a workflow. */
final class Seed {

    private int n;

    /** A case decided by a person who did not see the proposal: evidence. */
    void blind(Ledger ledger, String decision, String scope, int epoch, String proposal, String verdict, Instant at) {
        add(ledger, decision, scope, epoch, proposal, verdict, "ada", false, false, at, "watch");
    }

    void shown(Ledger ledger, String decision, String scope, int epoch, String proposal, String verdict, Instant at) {
        add(ledger, decision, scope, epoch, proposal, verdict, "ada", true, false, at, "suggest");
    }

    void byAgent(Ledger ledger, String decision, String scope, int epoch, String proposal, Instant at) {
        add(ledger, decision, scope, epoch, proposal, proposal, "agent", false, false, at, "act");
    }

    void malformed(Ledger ledger, String decision, String scope, int epoch, Instant at) {
        add(ledger, decision, scope, epoch, "escalate", "escalate", "ada", false, true, at, "watch");
    }

    String lastId() {
        return "c" + n;
    }

    private void add(Ledger ledger, String decision, String scope, int epoch, String proposal, String verdict, String decider, boolean shown, boolean bad, Instant at, String level) {
        String id = "c" + (++n);
        ledger.append(new Rec(id + "#case#1", decision, Rec.CASE, at, id, 1,
                Rec.map("scope", scope, "locator", "/runs/" + id, "step", "Main/s0", "fields", Map.of("amount", "20"), "level", level, "identity", "id" + epoch, "epoch", epoch)));
        ledger.append(new Rec(id + "#proposed#1", decision, Rec.PROPOSED, at, id, 1, Rec.map("choice", proposal, "reasoning", "r", "malformed", bad ? true : null)));
        ledger.append(new Rec(id + "#decided#1", decision, Rec.DECIDED, at, id, 1, Rec.map("verdict", verdict, "decider", decider, "shown", shown)));
    }
}
