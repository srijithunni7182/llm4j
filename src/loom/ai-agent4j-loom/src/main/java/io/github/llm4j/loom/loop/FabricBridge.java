package io.github.llm4j.loom.loop;

import io.github.llm4j.loom.explain.Why;
import io.github.llm4j.loom.travel.ScriptHash;
import io.github.llm4j.loom.whatif.WhatIf;
import io.github.llm4j.loom.whatif.WhatIfRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What Fabric's "Create test" and "Try a change" run. Fabric does not depend on Loom, so it calls
 * these two methods by name (reflection) and passes and receives plain strings and maps. Neither
 * ever writes into a source repository: a case is returned as text, or written into a folder that
 * is not inside the repository the script lives in.
 */
public final class FabricBridge {

    private FabricBridge() {}

    /**
     * Takes a case from a run in {@code store}.
     *
     * @param kind the incident kind that led here (tagged {@code incident:<kind>}), or null
     * @param injection true when Fabric flagged the run's content as a suspected injection
     * @param folder where to write the case, or null to only return it
     * @return {@code ok}, {@code message}, and when ok {@code name}, {@code text}, {@code masked},
     *     and {@code savedTo} (only when a folder was given)
     */
    public static Map<String, Object> capture(
            String store, String runId, String kind, boolean injection, String folder) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            RunRecord run = StoreReader.find(runId, Path.of(store));
            Policy policy = Policy.load(run.script());
            List<String> refused = policy.violations(run, Instant.now());
            if (!refused.isEmpty())
                return fail(out, run.id() + " may not be captured: " + String.join("; ", refused));
            Path to = null;
            if (folder != null) {
                to = Path.of(folder).toAbsolutePath().normalize();
                Path repo = Consent.root(run.script());
                if (to.startsWith(repo))
                    return fail(
                            out,
                            "the capture folder is inside the repository the script lives in ("
                                    + repo
                                    + "); Fabric never writes there. Use a folder outside it, or download the case.");
            }
            List<String> tags = new ArrayList<>();
            if (kind != null && kind.matches("[a-z][a-z0-9-]{0,40}")) tags.add("incident:" + kind);
            if (injection) tags.add("injection");
            String note =
                    injection
                            ? "Fabric flagged this run's content as a suspected injection; it is data in this case, never an instruction."
                            : null;
            Capture.Options options =
                    new Capture.Options(
                            to,
                            Capture.Expect.OBSERVED,
                            Set.of(),
                            Set.of(),
                            policy.denyFields(),
                            true,
                            note,
                            tags);
            Capture.Captured k = Capture.capture(run, options, to != null);
            if (k.conflict())
                return fail(
                        out,
                        k.file().getFileName()
                                + " is already in the folder and is not what this capture would write.");
            out.put("ok", true);
            out.put("message", k.unchanged() ? "Already captured." : "Captured.");
            out.put("name", k.file().getFileName().toString());
            out.put("text", k.text());
            out.put("masked", k.masked());
            if (to != null) out.put("savedTo", k.file().toString());
            return out;
        } catch (Exception e) {
            return fail(out, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * Replays a run with a change, from the recorded answers only (no model is called).
     *
     * @param at a statement number, an {@code n<number>} node, or a step label
     * @param sets variables to set at that point
     * @param answers a model's answer by agent or classifier name
     * @return {@code ok}, {@code message}, and when ok {@code report} (text)
     */
    public static Map<String, Object> whatIf(
            String store,
            String runId,
            String at,
            Map<String, String> sets,
            Map<String, String> answers) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            RunRecord run = StoreReader.find(runId, Path.of(store));
            if (!Files.isRegularFile(run.script()))
                return fail(out, run.script() + " is not there any more.");
            if (run.scriptHash() != null && !run.scriptHash().equals(ScriptHash.of(run.script())))
                return fail(
                        out,
                        "the script changed since this run started, so going back in the run would not be going back in this script.");
            List<WhatIf.Change> changes = new ArrayList<>();
            sets.forEach((k, v) -> changes.add(new WhatIf.Change("set", k, v, 1)));
            answers.forEach((k, v) -> changes.add(new WhatIf.Change("answer", k, v, 1)));
            if (changes.isEmpty()) return fail(out, "say what to change (a value or an answer).");
            if (at == null || at.isBlank())
                return fail(out, "say where in the run to go back to (a step id, n3, or a line).");
            Why.Selector selector =
                    at.matches("\\d+")
                            ? new Why.Selector(null, null, Integer.parseInt(at))
                            : at.matches("n\\d+")
                                    ? new Why.Selector(null, at, null)
                                    : new Why.Selector(at, null, null);
            WhatIf.Outcome o =
                    WhatIf.run(
                            new WhatIf.Request(
                                    run.script(),
                                    run.workflow(),
                                    run.inputs(),
                                    run.asJournal(),
                                    selector,
                                    changes,
                                    "recorded",
                                    null,
                                    Set.of()));
            out.put("ok", true);
            out.put("message", "Replayed from the recorded answers; no model was called.");
            out.put("report", WhatIfRenderer.text(o));
            return out;
        } catch (Exception e) {
            return fail(out, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static Map<String, Object> fail(Map<String, Object> out, String message) {
        out.clear();
        out.put("ok", false);
        out.put("message", message);
        return out;
    }
}
