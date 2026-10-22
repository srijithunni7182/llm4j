package io.github.llm4j.loom.inspect.map;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.autonomy.Level;
import io.github.llm4j.loom.autonomy.LevelState;
import io.github.llm4j.loom.autonomy.LevelStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each decision of a script stands on its autonomy ladder, read from the run's level store.
 * Read-only: it never creates a level, so a decision nobody has asked yet shows at the level it
 * would start at, marked as not started. Evidence against thresholds is a later addition; this is
 * the rung, who set it and why.
 */
public final class AutonomyView {

    private static final int MAX_SCOPES = 20;

    private AutonomyView() {}

    public static List<Map<String, Object>> of(LoomScript script, LevelStore levels) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DecisionDef d : script.getDecisions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", d.getName());
            if (d.getAgent() != null) m.put("agent", d.getAgent());
            m.put("ceiling", word(d.getCeiling()));
            Map<String, LevelState> scopes = levels == null ? Map.of() : levels.scopes(d.getName());
            boolean frozen = levels != null && levels.frozen(d.getName());
            Level lowest = null;
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map.Entry<String, LevelState> e : scopes.entrySet()) {
                LevelState s = e.getValue();
                Level shown =
                        Level.min(s.level(), d.getCeiling() == null ? Level.ACT : d.getCeiling());
                if (lowest == null || shown.compareTo(lowest) < 0) lowest = shown;
                if (list.size() < MAX_SCOPES) {
                    Map<String, Object> sm = new LinkedHashMap<>();
                    sm.put("scope", e.getKey());
                    sm.put("level", word(shown));
                    sm.put("since", String.valueOf(s.since()));
                    if (s.reason() != null) sm.put("reason", s.reason());
                    if (s.forced()) sm.put("forced", true);
                    list.add(sm);
                }
            }
            m.put("started", lowest != null);
            m.put("level", word(lowest != null ? lowest : d.getStartAt()));
            if (frozen) m.put("frozen", true);
            m.put("scopes", list);
            out.add(m);
        }
        return out;
    }

    private static String word(Level l) {
        return l == null ? null : l.word();
    }
}
