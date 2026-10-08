package io.github.llm4j.loom.autonomy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Retention: what a case record keeps once its fields have expired. */
final class Purge {

    private Purge() { }

    /** The record without the case's remembered values and its reasoning, when it is a case or proposal older than {@code before}. */
    static Rec apply(Rec r, Instant before) {
        if (!r.at().isBefore(before)) return r;
        if (!Rec.CASE.equals(r.kind()) && !Rec.PROPOSED.equals(r.kind())) return r;
        Map<String, Object> body = new LinkedHashMap<>(r.body());
        boolean changed = body.remove("fields") != null;
        changed |= body.remove("reasoning") != null;
        if (changed) body.put("purged", true);
        return new Rec(r.id(), r.decision(), r.kind(), r.at(), r.caseId(), r.generation(), body);
    }
}
