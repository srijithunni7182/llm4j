package io.github.llm4j.loom.ladder;

import io.github.llm4j.loom.autonomy.Ledger;
import io.github.llm4j.loom.autonomy.Rec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where each scope stands on its ladder, kept as records in the ledger itself: the standing is the latest level record of the scope. Nothing is
 * stored twice, a change of level is an event with its reason and the rules in force, and the memory, file and database ledgers all work as they are.
 */
public final class LevelLog {

    /**
     * @param rung    the earned rung (the ceiling and a freeze may hold the rung a case runs at lower)
     * @param version counts the changes of this scope; a record is written for each, so two writers cannot both make the same one
     * @param forced  set by hand beyond what the record supports
     * @param rules   the fingerprint of the rules under which the standing was last checked
     */
    public record Standing(int rung, String rungName, int version, int epoch, String identity, String servedModel, boolean forced, Instant at, String reason,
                           String rules) { }

    /** The rules of a standing written by the original engine, which kept no fingerprint: the standing is adopted as it is, not re-judged. */
    public static final String LEGACY = "";

    private final String decision;
    private final List<String> rungs;
    private final Ledger ledger;

    public LevelLog(String decision, List<String> rungs, Ledger ledger) {
        this.decision = decision;
        this.rungs = rungs;
        this.ledger = ledger;
    }

    private static String recordId(String decision, String scope, int version) {
        return decision + "#level#" + scope + "#" + version;
    }

    /** The scope's standing, or empty when it has never been seen. */
    public Optional<Standing> current(String scope) {
        return Optional.ofNullable(all().get(scope));
    }

    /** Every scope that has a standing. */
    public Map<String, Standing> all() {
        Map<String, Standing> latest = new LinkedHashMap<>();
        for (Rec r : ledger.recordsOfKind(decision, Rec.LEVEL)) {
            if (r.num("version") == null || !rungs.contains(r.str("to"))) continue;
            String scope = r.str("scope");
            Standing s = standing(r);
            Standing known = latest.get(scope);
            if (known == null || s.version() >= known.version()) latest.put(scope, s);
        }
        return latest;
    }

    private Standing standing(Rec r) {
        int index = r.num("toIndex") == null ? Math.max(0, rungs.indexOf(r.str("to"))) : r.num("toIndex").intValue();
        return new Standing(index, rungs.get(Math.min(index, rungs.size() - 1)), r.num("version").intValue(), r.num("epoch") == null ? 1 : r.num("epoch").intValue(),
                r.str("identity"), r.str("servedModel"), r.flag("forced"), r.at(), r.str("reason"), r.str("rules") == null ? LEGACY : r.str("rules"));
    }

    /**
     * Writes the next standing of the scope if it is still at {@code expected} (0 for a scope never seen). Returns false when someone else moved it
     * first: the record of a version is written under an id that names the version, so only one writer's record can stand.
     */
    public boolean write(String scope, int expected, Standing next, String kind, String from, String by, Instant at, String rulesText) {
        Optional<Standing> now = current(scope);
        int have = now.map(Standing::version).orElse(0);
        if (have != expected) return false;
        int version = expected + 1;
        String writer = java.util.UUID.randomUUID().toString();
        Rec rec = new Rec(recordId(decision, scope, version), decision, Rec.LEVEL, at, "", 1,
                Rec.map("scope", scope, "from", from, "to", next.rungName(), "toIndex", next.rung(), "kind", kind, "reason", next.reason(), "by", by,
                        "forced", next.forced() ? true : null, "epoch", next.epoch(), "identity", next.identity(), "servedModel", next.servedModel(),
                        "version", version, "rules", next.rules(), "rulesText", rulesText, "writer", writer));
        ledger.append(rec);
        for (Rec r : ledger.recordsOfKind(decision, Rec.LEVEL)) {
            if (r.id().equals(rec.id())) return writer.equals(r.str("writer"));
        }
        return false;
    }

    /** Whether autonomy is stopped for the decision: the latest freeze or unfreeze decides. */
    public boolean frozen() {
        boolean frozen = false;
        for (Rec r : ledger.recordsOfKind(decision, Rec.FROZEN)) frozen = r.flag("frozen");
        return frozen;
    }

    /** Why the decision is frozen, as the latest freeze said; null when it is not. */
    public String freezeReason() {
        String reason = null;
        for (Rec r : ledger.recordsOfKind(decision, Rec.FROZEN)) reason = r.flag("frozen") ? r.str("reason") : null;
        return reason;
    }

    public void setFrozen(boolean frozen, String reason, String by, Instant at) {
        ledger.append(new Rec(decision + (frozen ? "#freeze#" : "#unfreeze#") + at.toEpochMilli() + "#" + Integer.toHexString((reason + by).hashCode()), decision, Rec.FROZEN,
                at, "", 1, Rec.map("frozen", frozen, "reason", reason, "by", by, "scope", decision)));
    }
}
