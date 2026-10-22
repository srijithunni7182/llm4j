package io.github.llm4j.loom.autonomy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs a decision script through a fixed, deterministic series of cases and writes down, for each
 * case, the scope, the level the case ran at, what the agent proposed and what took effect. The
 * same source runs on the previous release (to make the golden files) and on this one (to compare),
 * so the only thing that differs between the two lines is the engine.
 */
final class LevelHistoryDriver {

    private LevelHistoryDriver() {}

    static List<String> history(Path dir, String script, int cases) {
        DecideHarness h =
                new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(), script);
        // the agent proposes approve except for a seventh of the amounts. The person agrees with it
        // throughout, except for a spell of bad judgement
        // (amounts 200 to 219, when every approval is rejected), so an agent climbs, is knocked
        // back, and climbs again
        h.agent =
                f ->
                        new String[] {
                            Integer.parseInt(f.getOrDefault("amount", "0")) % 7 == 0
                                    ? "reject"
                                    : "approve",
                            "because",
                            "0.9"
                        };
        h.person =
                f -> {
                    int amount = Integer.parseInt(f.getOrDefault("amount", "0"));
                    boolean agentApproves = amount % 7 != 0;
                    boolean spell = amount >= 200 && amount < 220;
                    return spell && agentApproves ? "reject" : agentApproves ? "approve" : "reject";
                };
        List<String> out = new ArrayList<>();
        for (int i = 0; i < cases; i++) {
            h.clock.advance(Duration.ofHours(5));
            String tier = i % 3 == 0 ? "basic" : "gold";
            Map<String, Object> vars = h.runCase("run-" + i, h.inputs(tier, 10 + i));
            String level = null, proposed = null;
            for (Map.Entry<String, Object> e : vars.entrySet()) {
                if (e.getKey().endsWith("_level")) level = String.valueOf(e.getValue());
                if (e.getKey().endsWith("_proposal")) proposed = String.valueOf(e.getValue());
            }
            out.add(i + " " + tier + " ran-at=" + level + " proposed=" + proposed);
        }
        // promotions proposed to an approver, and every change of level the ledger holds, in order
        // (not the note of which model the provider first named)
        for (Rec r : h.ledger.records("Refund")) {
            if (Rec.PROMOTION_PROPOSED.equals(r.kind()))
                out.add(
                        "proposed "
                                + r.str("scope")
                                + " to="
                                + r.str("to")
                                + " on "
                                + r.num("cases")
                                + " cases");
            if (Rec.LEVEL.equals(r.kind())
                    && !"first".equals(r.str("kind"))
                    && !"model".equals(r.str("kind")))
                out.add(
                        "level "
                                + r.str("scope")
                                + " "
                                + r.str("from")
                                + "->"
                                + r.str("to")
                                + " ("
                                + r.str("kind")
                                + ")");
        }
        return out;
    }
}
