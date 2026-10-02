package io.github.llm4j.loom.autonomy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One fact in the ledger. A ledger is append-only: a later fact about a case is a new record that names the case, never an edit, and the
 * case as it stands is the fold of its records in order (see {@link Cases}).
 *
 * @param id         derived from what the record says, so appending the same fact twice leaves one
 * @param caseId     the case it is about; empty for a level change, a freeze or a promotion proposal
 * @param generation the attempt of the run that produced it (a rewound case is decided again in a later generation)
 */
public record Rec(String id, String decision, String kind, Instant at, String caseId, int generation, Map<String, Object> body) {

    public static final String CASE = "case";
    public static final String PROPOSED = "proposed";
    public static final String DECIDED = "decided";
    public static final String OUTCOME = "outcome";
    public static final String LEVEL = "level";
    public static final String FROZEN = "frozen";
    public static final String PROMOTION_PROPOSED = "promotion_proposed";
    public static final String PROMOTION_DECIDED = "promotion_decided";

    public Rec {
        body = body == null ? Map.of() : Map.copyOf(body);
    }

    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) if (pairs[i + 1] != null) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    public String str(String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public Number num(String key) {
        return body.get(key) instanceof Number n ? n : null;
    }

    public boolean flag(String key) {
        return Boolean.TRUE.equals(body.get(key));
    }
}
