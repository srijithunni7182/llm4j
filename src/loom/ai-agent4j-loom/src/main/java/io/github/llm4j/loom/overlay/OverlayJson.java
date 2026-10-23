package io.github.llm4j.loom.overlay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The overlay as the JSON that goes beside a graph (version 1). */
public final class OverlayJson {

    private OverlayJson() {}

    public static final int VERSION = 1;

    public static Map<String, Object> tree(RunOverlay.Overlay overlay) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", VERSION);
        out.put("runs", overlay.runs());
        Map<String, Object> workflows = new LinkedHashMap<>();
        overlay.workflows()
                .forEach(
                        (name, stats) -> {
                            Map<String, Object> w = new LinkedHashMap<>();
                            Map<String, Object> nodes = new LinkedHashMap<>();
                            stats.nodes()
                                    .forEach(
                                            (id, n) -> {
                                                Map<String, Object> x = new LinkedHashMap<>();
                                                x.put("ran", n.ran());
                                                x.put("runs", n.runs());
                                                x.put("skipped", n.skipped());
                                                x.put("failures", n.failures());
                                                nodes.put(id, x);
                                            });
                            Map<String, Object> edges = new LinkedHashMap<>();
                            stats.edges()
                                    .forEach(
                                            (key, taken) -> {
                                                Map<String, Object> x = new LinkedHashMap<>();
                                                x.put("taken", taken);
                                                edges.put(key, x);
                                            });
                            w.put("nodes", nodes);
                            w.put("edges", edges);
                            workflows.put(name, w);
                        });
        out.put("workflows", workflows);
        List<Object> decisions = new ArrayList<>();
        for (RunOverlay.Decision d : overlay.decisions()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("workflow", d.workflow());
            x.put("node", d.node());
            x.put("kind", d.kind());
            if (d.condition() != null) x.put("condition", d.condition());
            if (d.result() != null) x.put("result", d.result());
            x.put("taken", d.taken());
            decisions.add(x);
        }
        out.put("decisions", decisions);
        List<Object> never = new ArrayList<>();
        for (RunOverlay.Unreached u : overlay.neverReached()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("workflow", u.workflow());
            x.put("node", u.node());
            x.put("label", u.label());
            if (u.line() > 0) x.put("line", u.line());
            never.add(x);
        }
        out.put("neverReached", never);
        return out;
    }
}
