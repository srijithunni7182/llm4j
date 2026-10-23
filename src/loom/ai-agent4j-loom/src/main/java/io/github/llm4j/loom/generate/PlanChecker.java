package io.github.llm4j.loom.generate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a plan before any file is written: every agent has a role and an allowed model, every tool
 * is a built-in kind or a declared stub, every effect has an approval or a stated reason for none.
 */
public final class PlanChecker {

    private PlanChecker() {}

    /**
     * The tool kinds the language has built in, and {@code class} (a stub the developer writes).
     */
    public static final Set<String> KINDS =
            Set.of(
                    "duckduckgo",
                    "serpapi",
                    "google_search",
                    "calculator",
                    "datetime",
                    "current_time",
                    "translate",
                    "transliterate",
                    "detect_language",
                    "speak",
                    "transcribe",
                    "webhook",
                    "email",
                    "http",
                    "file",
                    "shell",
                    "sql",
                    "openapi",
                    "knowledge_graph",
                    "skill_registry",
                    "class");

    public static final Set<String> REACHES = Set.of("none", "reads", "fetches", "writes", "sends");

    /** Reaches that change something beyond the run. */
    public static boolean isEffect(String reach) {
        return "writes".equals(reach) || "sends".equals(reach);
    }

    private static final Pattern NAME = Pattern.compile("[A-Z][A-Za-z0-9]*");
    private static final Pattern STEM = Pattern.compile("[a-z0-9][a-z0-9_-]*");

    /**
     * @param allowedModels the models that may be used, or null for any
     * @return what is wrong, one sentence each, or an empty list
     */
    public static List<String> check(Plan p, Set<String> allowedModels) {
        List<String> out = new ArrayList<>();
        if (p.name() == null || !NAME.matcher(p.name()).matches())
            out.add(
                    "The workflow name must start with a capital letter and have only letters and digits, not \""
                            + p.name()
                            + "\".");
        if (p.agents().isEmpty() && p.classifiers().isEmpty())
            out.add("The plan has no agent and no classifier, so nothing would do any work.");
        Set<String> names = new HashSet<>();
        Set<String> toolNames = new HashSet<>();
        for (Plan.Tool t : p.tools()) toolNames.add(t.name());
        for (Plan.Agent a : p.agents()) {
            unique(out, names, a.name(), "agent");
            if (a.name() == null || !NAME.matcher(a.name()).matches())
                out.add(
                        "Agent \""
                                + a.name()
                                + "\" needs a name that starts with a capital letter.");
            if (blank(a.role())) out.add("Agent " + a.name() + " has no role: say what it is for.");
            if (blank(a.model())) out.add("Agent " + a.name() + " has no model.");
            else if (allowedModels != null && !allowedModels.contains(a.model()))
                out.add(
                        "Agent "
                                + a.name()
                                + " uses "
                                + a.model()
                                + ", which is not an allowed model ("
                                + String.join(", ", new java.util.TreeSet<>(allowedModels))
                                + ").");
            if (a.prompt() == null || !STEM.matcher(a.prompt()).matches())
                out.add(
                        "Agent "
                                + a.name()
                                + " needs a prompt file name of lower-case letters, digits, - or _, not \""
                                + a.prompt()
                                + "\".");
            for (String t : a.tools())
                if (!toolNames.contains(t))
                    out.add(
                            "Agent "
                                    + a.name()
                                    + " uses the tool "
                                    + t
                                    + ", which the plan does not declare.");
        }
        for (Plan.Classifier c : p.classifiers()) {
            unique(out, names, c.name(), "classifier");
            if (blank(c.role())) out.add("Classifier " + c.name() + " has no role.");
            if (c.questions().isEmpty())
                out.add("Classifier " + c.name() + " asks nothing: list its questions.");
            if (c.backend() != null && !Set.of("jev", "chat").contains(c.backend()))
                out.add(
                        "Classifier "
                                + c.name()
                                + " has backend "
                                + c.backend()
                                + "; use jev or chat.");
        }
        Set<String> toolSeen = new HashSet<>();
        for (Plan.Tool t : p.tools()) {
            unique(out, toolSeen, t.name(), "tool");
            if (t.kind() == null || (!KINDS.contains(t.kind().toLowerCase()) && !t.stub()))
                out.add(
                        "Tool "
                                + t.name()
                                + " has kind "
                                + t.kind()
                                + ", which is not built in; declare it as a stub (\"stub\": true) to write it yourself.");
            if (t.reach() == null || !REACHES.contains(t.reach()))
                out.add(
                        "Tool "
                                + t.name()
                                + " must say what it reaches: none, reads, fetches, writes or sends.");
            if ("shell".equals(t.kind()) && blank(t.reason()))
                out.add("Tool " + t.name() + " is a shell tool: say why in \"reason\".");
        }
        Set<String> effectTools = new HashSet<>();
        for (Plan.Effect e : p.effects()) {
            effectTools.add(e.tool());
            if (!toolNames.contains(e.tool()))
                out.add("The effect of " + e.tool() + " names a tool the plan does not declare.");
            if (!"required".equals(e.approval()) && !"none".equals(e.approval()))
                out.add(
                        "The effect of "
                                + e.tool()
                                + " must have approval \"required\" or \"none\".");
            if ("none".equals(e.approval()) && blank(e.because()))
                out.add(
                        "The effect of "
                                + e.tool()
                                + " has no approval and no reason: say \"no approval, because ...\" or require one.");
        }
        for (Plan.Tool t : p.tools())
            if (isEffect(t.reach()) && !effectTools.contains(t.name()))
                out.add(
                        "Tool "
                                + t.name()
                                + " reaches \""
                                + t.reach()
                                + "\" but the plan lists no effect for it: say who approves it.");
        if (p.budget() == null
                || (p.budget().tokens() == null && p.budget().calls() == null)
                || (p.budget().tokens() != null && p.budget().tokens() <= 0)
                || (p.budget().calls() != null && p.budget().calls() <= 0))
            out.add("The plan has no budget: give tokens and/or calls per run.");
        if (p.tests().isEmpty())
            out.add("The plan lists no tests: a workflow is generated with its tests.");
        return out;
    }

    private static void unique(List<String> out, Set<String> seen, String name, String what) {
        if (name != null && !seen.add(name))
            out.add("The " + what + " name " + name + " is used twice.");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
