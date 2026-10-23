package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks one case's {@code expect} against what the run did, and reports every difference (not only
 * the first) as expected, actual and where. A case with no {@code expect} asserts only that the run
 * completed and that nothing unscripted was asked for.
 */
final class Assertions {

    private Assertions() {}

    /** One thing that did not match. */
    record Failure(String what, String message) {}

    static List<Failure> evaluate(TestFile.Case c, CaseRunner.Outcome o) {
        List<Failure> failures = new ArrayList<>();
        for (String v : o.recorder().violations()) failures.add(new Failure("unscripted", v));
        JsonNode expect = c.get("expect");
        String wantFinal =
                expect != null && expect.has("final") ? expect.get("final").asText() : "completed";
        if (!wantFinal.equals(o.status()))
            failures.add(
                    new Failure(
                            "final",
                            "expected the run to end "
                                    + wantFinal
                                    + ", it ended "
                                    + o.status()
                                    + (o.error() != null ? ": " + o.error() : "")));
        if (expect == null) return failures;
        if (expect.has("result")) result(expect, o, failures);
        if (expect.has("route") || expect.has("never")) route(expect, o, failures);
        if (expect.has("calls")) calls(expect.get("calls"), o, failures);
        if (expect.has("context")) context(expect.get("context"), o, failures);
        if (expect.has("models_used")) modelsUsed(expect.get("models_used"), o, failures);
        if (expect.has("attempts")) calls(expect.get("attempts"), o, failures, "attempts");
        if (expect.has("ran")) ran(expect.get("ran"), o, failures);
        if (expect.has("asked")) asked(expect.get("asked"), o, failures);
        if (expect.has("effects")) effects(expect.get("effects"), o, failures);
        if (expect.has("elapsed_virtual")) elapsed(expect.get("elapsed_virtual"), o, failures);
        if (expect.has("tokens")) tokens(expect.get("tokens"), o, failures);
        return failures;
    }

    private static void result(JsonNode expect, CaseRunner.Outcome o, List<Failure> failures) {
        JsonNode want = expect.get("result");
        String problem = Matchers.mismatch(want, o.result());
        if (problem == null
                && expect.has("exact")
                && expect.get("exact").asBoolean()
                && want.isObject()) {
            Object actual = o.result();
            if (actual instanceof String s)
                actual = Values.parseObject(s).map(m -> (Object) m).orElse(actual);
            if (actual instanceof Map<?, ?> m) {
                List<String> extra = new ArrayList<>();
                for (Object k : m.keySet())
                    if (!want.has(String.valueOf(k))) extra.add(String.valueOf(k));
                if (!extra.isEmpty())
                    problem = "the result has fields the test did not expect: " + extra;
            }
        }
        if (problem != null) failures.add(new Failure("result", "result: " + problem));
    }

    private static void route(JsonNode expect, CaseRunner.Outcome o, List<Failure> failures) {
        List<String> ran = o.recorder().route();
        if (expect.has("route")) {
            List<String> want = new ArrayList<>();
            expect.get("route").forEach(n -> want.add(n.asText()));
            boolean exact = expect.has("route_exact") && expect.get("route_exact").asBoolean();
            String problem = RouteDiff.mismatch(expand(want, ran), ran, exact);
            if (problem != null) failures.add(new Failure("route", "route: " + problem));
        }
        if (expect.has("never"))
            for (JsonNode n : expect.get("never"))
                for (String step : ran)
                    if (Labels.matches(n.asText(), step)) {
                        failures.add(
                                new Failure(
                                        "never",
                                        "never: \""
                                                + n.asText()
                                                + "\" must not run, but \""
                                                + step
                                                + "\" did"));
                        break;
                    }
    }

    /**
     * The expected steps written as the labels that ran: a bare kind or an id the test used is
     * replaced by the label of the step that matched it, so the comparison is on labels.
     */
    private static List<String> expand(List<String> want, List<String> ran) {
        List<String> out = new ArrayList<>();
        int at = 0;
        for (String w : want) {
            String chosen = w;
            for (int j = at; j < ran.size(); j++)
                if (Labels.matches(w, ran.get(j))) {
                    chosen = ran.get(j);
                    at = j + 1;
                    break;
                }
            out.add(chosen);
        }
        return out;
    }

    private static void calls(JsonNode want, CaseRunner.Outcome o, List<Failure> failures) {
        calls(want, o, failures, "calls");
    }

    /**
     * Counts of calls per name. Every call counts, one that failed as much as one that worked, so
     * {@code attempts} and {@code calls} read the same number: a step retried twice made three.
     */
    private static void calls(
            JsonNode want, CaseRunner.Outcome o, List<Failure> failures, String key) {
        Map<String, Integer> actual = new LinkedHashMap<>();
        actual.putAll(o.recorder().taskCalls());
        actual.putAll(o.recorder().toolCalls());
        actual.putAll(o.recorder().callCounts());
        for (Iterator<Map.Entry<String, JsonNode>> it = want.fields(); it.hasNext(); ) {
            var e = it.next();
            int got = actual.getOrDefault(e.getKey(), 0);
            String problem = Matchers.mismatch(e.getValue(), got);
            if (problem != null)
                failures.add(
                        new Failure(
                                key,
                                key + "." + e.getKey() + ": " + problem.replaceFirst("^: ", "")));
        }
    }

    private static void context(JsonNode want, CaseRunner.Outcome o, List<Failure> failures) {
        for (Iterator<Map.Entry<String, JsonNode>> it = want.fields(); it.hasNext(); ) {
            var e = it.next();
            Object got = Values.path(o.variables(), e.getKey());
            String problem = Matchers.mismatch(e.getValue(), got);
            if (problem != null)
                failures.add(new Failure("context", "context." + e.getKey() + ": " + problem));
        }
    }

    private static void modelsUsed(JsonNode want, CaseRunner.Outcome o, List<Failure> failures) {
        for (Iterator<Map.Entry<String, JsonNode>> it = want.fields(); it.hasNext(); ) {
            var e = it.next();
            String got = o.modelsUsed().get(e.getKey());
            String problem = Matchers.mismatch(e.getValue(), got);
            if (problem != null)
                failures.add(
                        new Failure("models_used", "models_used." + e.getKey() + ": " + problem));
        }
    }

    /** The handlers that took over, as an ordered subsequence. */
    private static void ran(JsonNode want, CaseRunner.Outcome o, List<Failure> failures) {
        List<String> expected = new ArrayList<>();
        want.forEach(
                n ->
                        expected.add(
                                n.asText()
                                        .replaceFirst(
                                                "^on_failure of intentclassify ",
                                                "on_failure of classify ")));
        List<String> actual = o.recorder().handlers();
        String problem = RouteDiff.mismatch(expected, actual, false);
        if (problem != null)
            failures.add(
                    new Failure(
                            "ran",
                            "ran: "
                                    + (actual.isEmpty()
                                            ? "no handler took over, expected " + expected
                                            : problem)));
    }

    /**
     * The questions put to a person, in order: each a text (contains) or {prompt, kind} matchers.
     */
    private static void asked(JsonNode want, CaseRunner.Outcome o, List<Failure> failures) {
        List<Map<String, String>> actual = o.recorder().asked();
        if (want.isTextual() && want.asText().equals("none")) {
            if (!actual.isEmpty())
                failures.add(
                        new Failure(
                                "asked",
                                "asked: expected nobody to be asked, but "
                                        + actual.size()
                                        + " question(s) were put to a person: "
                                        + prompts(actual)));
            return;
        }
        if (!want.isArray()) {
            failures.add(new Failure("asked", "asked: a list of questions, or none"));
            return;
        }
        if (want.size() != actual.size()) {
            failures.add(
                    new Failure(
                            "asked",
                            "asked: expected "
                                    + want.size()
                                    + " question(s), a person was asked "
                                    + actual.size()
                                    + (actual.isEmpty() ? "" : ": " + prompts(actual))));
            return;
        }
        for (int i = 0; i < want.size(); i++) {
            JsonNode w = want.get(i);
            Map<String, String> a = actual.get(i);
            String problem;
            if (w.isTextual())
                problem = Matchers.mismatch(YAMLContains.of(w.asText()), a.get("prompt"));
            else {
                problem = null;
                if (w.has("prompt")) problem = Matchers.mismatch(w.get("prompt"), a.get("prompt"));
                if (problem == null && w.has("kind"))
                    problem = Matchers.mismatch(w.get("kind"), a.get("kind"));
            }
            if (problem != null)
                failures.add(new Failure("asked", "asked[" + (i + 1) + "]: " + problem));
        }
    }

    private static String prompts(List<Map<String, String>> asked) {
        List<String> out = new ArrayList<>();
        for (Map<String, String> m : asked)
            out.add("\"" + Values.cut(m.get("prompt").replace('\n', ' '), 80, false) + "\"");
        return out.toString();
    }

    /** What acting tools would have done: {@code none}, or a list matched in order. */
    private static void effects(JsonNode want, CaseRunner.Outcome o, List<Failure> failures) {
        List<Effect> actual = o.recorder().effects();
        if (want.isTextual() && want.asText().equals("none")) {
            if (!actual.isEmpty())
                failures.add(
                        new Failure(
                                "effects",
                                "effects: expected none, but "
                                        + actual.size()
                                        + " would have happened: "
                                        + describe(actual)));
            return;
        }
        if (want.isObject() && want.has("count")) {
            String p = Matchers.mismatch(want.get("count"), actual.size());
            if (p != null) failures.add(new Failure("effects", "effects.count: " + p));
            return;
        }
        if (!want.isArray()) {
            failures.add(new Failure("effects", "effects: none, a count, or a list"));
            return;
        }
        if (want.size() != actual.size()) {
            failures.add(
                    new Failure(
                            "effects",
                            "effects: expected "
                                    + want.size()
                                    + ", "
                                    + actual.size()
                                    + " would have happened"
                                    + (actual.isEmpty() ? "" : ": " + describe(actual))));
            return;
        }
        for (int i = 0; i < want.size(); i++) {
            Effect e = actual.get(i);
            Map<String, Object> as = new LinkedHashMap<>();
            as.put("tool", e.tool());
            as.put("kind", e.kind());
            as.put("target", e.target());
            as.put("digest", e.digest());
            String p = Matchers.mismatch(want.get(i), as);
            if (p != null) failures.add(new Failure("effects", "effects[" + (i + 1) + "]: " + p));
        }
    }

    private static String describe(List<Effect> effects) {
        List<String> out = new ArrayList<>();
        for (Effect e : effects)
            out.add(
                    e.tool()
                            + " ("
                            + e.kind()
                            + (e.target().isEmpty() ? "" : " to " + e.target())
                            + ")");
        return out.toString();
    }

    /**
     * How long the case waited in virtual time: a duration such as {@code 5m}, or a matcher on
     * seconds.
     */
    private static void elapsed(JsonNode want, CaseRunner.Outcome o, List<Failure> failures) {
        long seconds = o.elapsedVirtual().toSeconds();
        String problem;
        if (want.isTextual()) {
            java.time.Duration d = Durations.parse(want.asText());
            problem =
                    d == null
                            ? "'" + want.asText() + "' is not a duration such as 30s, 5m or 2h"
                            : d.equals(o.elapsedVirtual())
                                    ? null
                                    : "expected "
                                            + want.asText()
                                            + ", the case waited "
                                            + Durations.show(o.elapsedVirtual());
        } else problem = Matchers.mismatch(want, seconds);
        if (problem != null)
            failures.add(new Failure("elapsed_virtual", "elapsed_virtual: " + problem));
    }

    /** Tokens the scripted calls used: a matcher, or {@code {max: N}}. */
    private static void tokens(JsonNode want, CaseRunner.Outcome o, List<Failure> failures) {
        JsonNode m = want;
        if (want.isObject() && (want.has("max") || want.has("min"))) {
            if (want.has("max")) {
                String p =
                        Matchers.mismatch(
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                        .createObjectNode()
                                        .set("lte", want.get("max")),
                                o.recorder().tokens());
                if (p != null) failures.add(new Failure("tokens", "tokens: " + p));
            }
            if (want.has("min")) {
                String p =
                        Matchers.mismatch(
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                        .createObjectNode()
                                        .set("gte", want.get("min")),
                                o.recorder().tokens());
                if (p != null) failures.add(new Failure("tokens", "tokens: " + p));
            }
            return;
        }
        String p = Matchers.mismatch(m, o.recorder().tokens());
        if (p != null) failures.add(new Failure("tokens", "tokens: " + p));
    }
}
