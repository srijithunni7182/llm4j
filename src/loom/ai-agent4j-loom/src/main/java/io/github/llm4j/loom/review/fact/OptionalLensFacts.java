package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.BudgetDef;
import io.github.llm4j.loom.ast.DecideStmt;
import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.prompt.PromptCatalog;
import io.github.llm4j.loom.prompt.PromptSupport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Facts for the four optional lenses (governance, maintainability, human oversight, blast radius):
 * which models are deprecated by the project's own declaration, which agents are copies of another,
 * which prompts are versioned, which number is repeated in conditions, what follows a decision, and
 * which acting steps sit inside a repeat that nothing caps.
 */
final class OptionalLensFacts implements FactProvider {

    private static final Pattern THRESHOLD =
            Pattern.compile("(?:[<>]=?|==|!=)\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern VERSION_FILE = Pattern.compile("v\\d+\\.md");

    @Override
    public String name() {
        return "optional lenses";
    }

    @Override
    public void provide(FactInput input, FactBase.Builder out) {
        LoomScript script = input.review().script();
        models(input, out);
        agents(input, out);
        thresholds(script, out);
        steps(script, out);
    }

    // ---- governance

    private static void models(FactInput input, FactBase.Builder out) {
        List<String> declared =
                input.settings()
                        .value("data.deprecated-models")
                        .map(
                                v ->
                                        Arrays.stream(v.split(","))
                                                .map(s -> s.strip().toLowerCase(Locale.ROOT))
                                                .filter(s -> !s.isEmpty())
                                                .toList())
                        .orElse(null);
        for (Entity m : out.entitiesOf("model")) {
            String id = (String) m.get("id");
            out.set(m, "known", ModelCatalog.knows(id));
            out.set(m, "nearest", ModelCatalog.nearMiss(id));
            out.set(
                    m,
                    "deprecated",
                    declared == null
                            ? Unknown.INSTANCE
                            : (Object) declared.contains(id.toLowerCase(Locale.ROOT)));
        }
    }

    // ---- maintainability and oversight, per agent

    private static void agents(FactInput input, FactBase.Builder out) {
        LoomScript script = input.review().script();
        PromptCatalog prompts = null;
        try {
            prompts =
                    PromptSupport.catalog(
                            script, input.review().baseDir().resolve(input.review().file()), null);
        } catch (RuntimeException e) {
            // no prompt folder, or one that cannot be read: versioning stays unknown
        }
        Map<String, Boolean> versioned = new HashMap<>();
        for (AgentDef a : script.getAgents()) {
            if (prompts == null || a.getPrompt() == null || a.getPrompt().isBlank()) continue;
            var r = prompts.resolve(a.getPrompt());
            if (r.isPresent() && r.get().file() != null)
                versioned.put(
                        a.getName(),
                        VERSION_FILE.matcher(r.get().file().getFileName().toString()).matches());
        }
        boolean anyVersioned = versioned.containsValue(true);
        Map<String, String> firstWithKey = new HashMap<>();
        for (AgentDef a : script.getAgents()) {
            Entity e = out.find("agent:" + a.getName()).orElse(null);
            if (e == null) continue;
            out.set(
                    e,
                    "unversionedAmongVersioned",
                    versioned.containsKey(a.getName())
                            ? (Object) (anyVersioned && !versioned.get(a.getName()))
                            : Unknown.INSTANCE);
            String key = key(a);
            String earlier = key == null ? null : firstWithKey.putIfAbsent(key, a.getName());
            out.set(e, "identicalTo", earlier == null ? "" : earlier);
            boolean noisy = false;
            for (String t : a.getApprove()) {
                Entity tool = out.find("tool:" + t).orElse(null);
                if (tool == null || !Boolean.TRUE.equals(tool.get("reachKnown"))) continue;
                Object reach = tool.get("reach");
                noisy |= "none".equals(reach) || "reads".equals(reach);
            }
            out.set(e, "approvesLowReachTool", noisy);
        }
    }

    /** What makes two agents the same agent under another name; null for one with no prompt. */
    private static String key(AgentDef a) {
        String prompt = a.getSystemPrompt() != null ? a.getSystemPrompt() : a.getPrompt();
        if (prompt == null || prompt.isBlank()) return null;
        List<String> tools = new ArrayList<>(a.getTools());
        tools.sort(String::compareTo);
        List<String> approve = new ArrayList<>(a.getApprove());
        approve.sort(String::compareTo);
        return a.getModel()
                + "\u0000"
                + prompt.strip()
                + "\u0000"
                + tools
                + "\u0000"
                + approve
                + "\u0000"
                + a.isApproveAll();
    }

    // ---- maintainability: a number repeated in conditions

    private static void thresholds(LoomScript script, FactBase.Builder out) {
        Map<String, Long> counts = new TreeMap<>();
        for (WorkflowDef w : script.getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        String c =
                                s instanceof AltStmt a
                                        ? a.getCondition()
                                        : s instanceof LoopStmt l ? l.getCondition() : null;
                        if (c == null) return;
                        Matcher m = THRESHOLD.matcher(c);
                        while (m.find()) counts.merge(m.group(1), 1L, Long::sum);
                    });
        String top = "";
        long most = 0;
        for (var e : counts.entrySet())
            if (e.getValue() > most) {
                top = e.getKey();
                most = e.getValue();
            }
        Entity s = out.find("script").orElse(null);
        if (s == null) return;
        out.set(s, "repeatedThreshold", top);
        out.set(s, "repeatedThresholdCount", most);
    }

    // ---- oversight and blast radius, per step

    private record Position(boolean unboundedRepeat, boolean capped) {}

    private static void steps(LoomScript script, FactBase.Builder out) {
        boolean scriptCap = script.getBudget() != null && script.getBudget().getCalls() != null;
        Map<String, DecisionDef> decisions = new LinkedHashMap<>();
        script.getDecisions().forEach(d -> decisions.put(d.getName(), d));
        for (WorkflowDef w : script.getWorkflows()) {
            Map<Statement, Position> positions = new IdentityHashMap<>();
            position(w.getStatements(), false, scriptCap, positions);
            List<Statement> all = new ArrayList<>();
            StatementWalker.walk(w.getStatements(), all::add);
            for (Entity step : out.entitiesOf("step")) {
                Object wf = step.get("workflow");
                if (!(wf instanceof Entity we) || !w.getName().equals(we.get("name"))) continue;
                long line = step.get("line") instanceof Long l ? l : 0;
                String kind = String.valueOf(step.get("kind"));
                Statement s = find(all, kind, (int) line);
                Position p = s == null ? null : positions.get(s);
                out.set(step, "inUnboundedRepeat", p != null && p.unboundedRepeat());
                out.set(step, "effectCapped", p != null && p.capped());
                out.set(
                        step,
                        "decideLacksLadder",
                        s instanceof DecideStmt d && lacksLadder(d, decisions));
                out.set(
                        step,
                        "followedByEffect",
                        s instanceof DecideStmt && effectAfter(all, (int) line, out));
                out.set(
                        step,
                        "answerCompared",
                        s instanceof HumanPromptStmt h && compared(all, h.getVariableName()));
            }
        }
    }

    private static void position(
            List<Statement> list,
            boolean unboundedRepeat,
            boolean capped,
            Map<Statement, Position> into) {
        for (Statement s : list == null ? List.<Statement>of() : list) {
            boolean unb = unboundedRepeat;
            boolean cap = capped || ownCap(s);
            if (s instanceof ForEachStmt) unb = true;
            if (s instanceof LoopStmt l && l.getMaxIterations() <= 0) unb = true;
            into.put(s, new Position(unb, cap));
            for (List<Statement> nested : StatementWalker.nested(s))
                position(nested, unb, cap, into);
        }
    }

    private static boolean ownCap(Statement s) {
        BudgetDef b =
                s instanceof DelegateStmt d
                        ? d.getBudget()
                        : s instanceof LoopStmt l
                                ? l.getBudget()
                                : s instanceof ForEachStmt f ? f.getBudget() : null;
        return b != null && b.getCalls() != null;
    }

    private static Statement find(List<Statement> all, String kind, int line) {
        for (Statement s : all) {
            if (s.getLine() != line) continue;
            String k =
                    s instanceof DelegateStmt
                            ? "delegate"
                            : s instanceof DecideStmt
                                    ? "decide"
                                    : s instanceof HumanPromptStmt ? "human_prompt" : null;
            if (k != null && k.equals(kind)) return s;
        }
        return null;
    }

    private static boolean lacksLadder(DecideStmt d, Map<String, DecisionDef> decisions) {
        DecisionDef def = decisions.get(d.getDecision());
        // a decision with nothing written gets a default trust ladder of its own, which is not a
        // choice the author made
        return def != null
                && !def.isTrustGiven()
                && (def.getLadder() == null || def.isLadderFromTrust());
    }

    private static boolean effectAfter(List<Statement> all, int line, FactBase.Builder out) {
        for (Statement s : all)
            if (s.getLine() > line
                    && s instanceof DelegateStmt d
                    && !d.hasDynamicTarget()
                    && out.find("agent:" + d.getTargetAgent())
                            .map(a -> Boolean.TRUE.equals(a.get("hasEffects")))
                            .orElse(false)) return true;
        return false;
    }

    private static boolean compared(List<Statement> all, String variable) {
        if (variable == null) return false;
        Pattern word =
                Pattern.compile(
                        "(?<![A-Za-z0-9_.])" + Pattern.quote(variable) + "(?![A-Za-z0-9_])");
        for (Statement s : all) {
            String c =
                    s instanceof AltStmt a
                            ? a.getCondition()
                            : s instanceof LoopStmt l ? l.getCondition() : null;
            if (c != null && word.matcher(c).find()) return true;
        }
        return false;
    }
}
