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
                                "calls",
                                "calls." + e.getKey() + ": " + problem.replaceFirst("^: ", "")));
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
}
