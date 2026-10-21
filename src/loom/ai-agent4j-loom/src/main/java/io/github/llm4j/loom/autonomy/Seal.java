package io.github.llm4j.loom.autonomy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Makes the record of levels, promotions and freezes tamper-evident. Each such record the runtime
 * writes carries a seal (an HMAC of its content under a key kept with the store, or taken from
 * {@code LOOM_LEDGER_KEY}); an <em>anchor</em> record states the hash of every such record before
 * it, so a record that is edited, inserted or removed anywhere before the anchor is found, whether
 * it carried a seal (the new release's) or not (the previous release's).
 *
 * <p>What it is: evidence that the file was changed by something that did not hold the key. What it
 * is not: protection against a person who can read the key file and the ledger, or against the loss
 * of the last records. Setting {@code LOOM_LEDGER_KEY} keeps the key out of the store.
 */
public final class Seal {

    /** The kinds of record that decide where an agent stands. */
    public static final Set<String> KINDS =
            Set.of(
                    Rec.LEVEL,
                    Rec.PROMOTION_PROPOSED,
                    Rec.PROMOTION_DECIDED,
                    Rec.FROZEN,
                    Rec.SEAL_BASE);

    private static final String[] KIND_LIST = KINDS.toArray(String[]::new);
    private static final ObjectMapper JSON =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile byte[] jvmKey;

    private Seal() {}

    /**
     * What verification found. {@code sealed} is false for a record the previous release wrote and
     * nothing has anchored yet.
     */
    public record Result(boolean ok, boolean sealed, String record, String why, int records) {
        static Result good(boolean sealed, int records) {
            return new Result(
                    true,
                    sealed,
                    null,
                    sealed ? "verified" : "written by the previous release; not yet sealed",
                    records);
        }

        static Result bad(String record, String why, int records) {
            return new Result(false, true, record, why, records);
        }
    }

    // ---- the key
    // -------------------------------------------------------------------------------------------------------------

    /** A key of this JVM's own, for a ledger that keeps none (a test, a one-shot run). */
    public static byte[] processKey() {
        if (jvmKey == null) {
            byte[] k = new byte[32];
            RANDOM.nextBytes(k);
            jvmKey = k;
        }
        return jvmKey;
    }

    public static byte[] newKey() {
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        return k;
    }

    private static byte[] key(Ledger ledger) {
        String env = System.getenv("LOOM_LEDGER_KEY");
        return env != null && !env.isBlank()
                ? env.getBytes(StandardCharsets.UTF_8)
                : ledger.sealKey();
    }

    // ---- sealing
    // -------------------------------------------------------------------------------------------------------------

    private static String canonical(Rec r, boolean withSeal) {
        Map<String, Object> body = new LinkedHashMap<>(r.body());
        if (!withSeal) body.remove("seal");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("decision", r.decision());
        m.put("kind", r.kind());
        m.put("at", r.at().toString());
        m.put("caseId", r.caseId());
        m.put("generation", r.generation());
        m.put("body", body);
        try {
            return JSON.writeValueAsString(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mac(byte[] key, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hashOf(List<Rec> records) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Rec r : records)
                md.update((canonical(r, true) + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Rec sealed(byte[] key, Rec r) {
        Map<String, Object> body = new LinkedHashMap<>(r.body());
        body.remove("seal");
        Rec bare =
                new Rec(r.id(), r.decision(), r.kind(), r.at(), r.caseId(), r.generation(), body);
        body.put("seal", mac(key, canonical(bare, false)));
        return new Rec(r.id(), r.decision(), r.kind(), r.at(), r.caseId(), r.generation(), body);
    }

    private static boolean valid(byte[] key, Rec r) {
        String seal = r.str("seal");
        return seal != null && seal.equals(mac(key, canonical(r, false)));
    }

    private static List<Rec> relevant(Ledger ledger, String decision) {
        return ledger.recordsOfKind(decision, KIND_LIST);
    }

    private static List<Rec> withoutBases(List<Rec> records, int upTo) {
        return records.subList(0, upTo).stream()
                .filter(r -> !Rec.SEAL_BASE.equals(r.kind()))
                .toList();
    }

    /**
     * Appends a record. One that decides where an agent stands is sealed, and the record is
     * anchored the first time.
     */
    public static void append(Ledger ledger, Rec record) {
        if (!KINDS.contains(record.kind())) {
            ledger.append(record);
            return;
        }
        byte[] key = key(ledger);
        List<Rec> existing = relevant(ledger, record.decision());
        if (existing.stream().noneMatch(r -> Rec.SEAL_BASE.equals(r.kind())))
            ledger.append(anchor(key, record.decision(), existing, record.at(), 1, null, null));
        ledger.append(Rec.SEAL_BASE.equals(record.kind()) ? record : sealed(key, record));
    }

    private static Rec anchor(
            byte[] key,
            String decision,
            List<Rec> existing,
            Instant at,
            int number,
            String by,
            String reason) {
        List<Rec> covered = existing.stream().filter(r -> !Rec.SEAL_BASE.equals(r.kind())).toList();
        Rec base =
                new Rec(
                        decision + "#sealbase#" + number,
                        decision,
                        Rec.SEAL_BASE,
                        at,
                        "",
                        1,
                        Rec.map(
                                "covers",
                                covered.size(),
                                "hash",
                                hashOf(covered),
                                "by",
                                by,
                                "reason",
                                reason));
        return sealed(key, base);
    }

    /**
     * An operator has looked at the record and accepts it as it stands: the next records are sealed
     * from here.
     */
    public static void accept(
            Ledger ledger, String decision, Instant at, String by, String reason) {
        byte[] key = key(ledger);
        List<Rec> existing = relevant(ledger, decision);
        int number =
                (int) existing.stream().filter(r -> Rec.SEAL_BASE.equals(r.kind())).count() + 1;
        ledger.append(anchor(key, decision, existing, at, number, by, reason));
    }

    // ---- verifying
    // -----------------------------------------------------------------------------------------------------------

    public static Result verify(Ledger ledger, String decision) {
        List<Rec> rs = relevant(ledger, decision);
        byte[] key = key(ledger);
        int lastBase = -1;
        for (int i = 0; i < rs.size(); i++)
            if (Rec.SEAL_BASE.equals(rs.get(i).kind())) lastBase = i;

        // what is before the latest anchor is covered by its hash (an operator who accepted it has
        // accepted its gaps); from there on the versions must go on counting
        Result versions = versions(rs, lastBase);
        if (versions != null) return versions;
        if (lastBase < 0) {
            for (Rec r : rs)
                if (r.str("seal") != null)
                    return Result.bad(
                            r.id(), "a sealed record with no anchor before it", rs.size());
            return Result.good(false, rs.size());
        }
        Rec base = rs.get(lastBase);
        if (!valid(key, base))
            return Result.bad(
                    base.id(),
                    "the anchor's seal is wrong (a different key, or the anchor was changed)",
                    rs.size());
        List<Rec> before = withoutBases(rs, lastBase);
        if (base.num("covers") == null || base.num("covers").intValue() != before.size()) {
            return Result.bad(
                    base.id(),
                    "the anchor covers "
                            + base.num("covers")
                            + " records and "
                            + before.size()
                            + " are there: one was added or removed",
                    rs.size());
        }
        if (!hashOf(before).equals(base.str("hash")))
            return Result.bad(base.id(), "a record before the anchor was changed", rs.size());
        for (int i = lastBase + 1; i < rs.size(); i++) {
            Rec r = rs.get(i);
            if (!valid(key, r))
                return Result.bad(
                        r.id(),
                        r.str("seal") == null
                                ? "a record the runtime did not write (it has no seal)"
                                : "a record was changed (its seal is wrong)",
                        rs.size());
        }
        return Result.good(true, rs.size());
    }

    /**
     * The versions of a scope's level records count up by one: a gap means a record was removed or
     * added. Judged after the latest anchor; before it the anchor's hash speaks.
     */
    private static Result versions(List<Rec> rs, int lastBase) {
        Map<String, Integer> last = new LinkedHashMap<>();
        for (int i = 0; i < rs.size(); i++) {
            Rec r = rs.get(i);
            if (!Rec.LEVEL.equals(r.kind()) || r.num("version") == null) continue;
            String scope = String.valueOf(r.str("scope"));
            int v = r.num("version").intValue();
            if (i > lastBase && lastBase >= 0 || lastBase < 0) {
                int expected = last.getOrDefault(scope, 0) + 1;
                if (v != expected)
                    return Result.bad(
                            r.id(),
                            "version "
                                    + v
                                    + " of scope "
                                    + scope
                                    + " follows version "
                                    + (expected - 1)
                                    + ": a record is missing or was added",
                            rs.size());
            }
            last.put(scope, v);
        }
        return null;
    }
}
