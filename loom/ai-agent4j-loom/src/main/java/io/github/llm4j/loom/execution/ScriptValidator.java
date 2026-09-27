package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.skill.AgentSkill;
import io.github.llm4j.agent.skill.FileSystemSkillLoader;
import io.github.llm4j.loom.ast.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Every check a script must pass before it runs, collected in one pass so authors see all problems at
 * once. Nothing here calls a model, embeds, or starts a server — {@code weave check} runs exactly this.
 *
 * <p>Principle: a script never says something the runtime ignores. Unsupported features are errors
 * (warnings in lenient mode); unknown names and missing secrets are always errors.
 */
public class ScriptValidator {

    public enum Severity { ERROR, WARNING }

    /** One problem. {@code construct} names what it is about, e.g. {@code agent Writer}. */
    public record Problem(int line, String construct, String message, Severity severity) {
        @Override
        public String toString() {
            return (line > 0 ? "line " + line + ": " : "") + construct + ": " + message
                    + (severity == Severity.WARNING ? " (warning)" : "");
        }
    }

    /** What the script is checked against. */
    public static final class Context {
        Set<String> registeredTools = Set.of();
        Function<String, String> env = System::getenv;
        boolean lenient;
        boolean humanInterface;
        final List<Consumer<Checker>> extraChecks = new ArrayList<>();

        public Context registeredTools(Set<String> names) {
            this.registeredTools = Set.copyOf(names);
            return this;
        }

        public Context env(Function<String, String> env) {
            this.env = env;
            return this;
        }

        public Context lenient(boolean lenient) {
            this.lenient = lenient;
            return this;
        }

        public Context humanInterface(boolean present) {
            this.humanInterface = present;
            return this;
        }

        /** More checks (e.g. tools and knowledge), run after the built-in ones. */
        public Context check(Consumer<Checker> check) {
            extraChecks.add(check);
            return this;
        }
    }

    /** Handed to checks: the script, the context, and where to report. */
    public static final class Checker {
        private final LoomScript script;
        private final Context context;
        private final List<Problem> problems = new ArrayList<>();

        Checker(LoomScript script, Context context) {
            this.script = script;
            this.context = context;
        }

        public LoomScript script() {
            return script;
        }

        public Context context() {
            return context;
        }

        public Function<String, String> env() {
            return context.env;
        }

        public void error(int line, String construct, String message) {
            problems.add(new Problem(line, construct, message, Severity.ERROR));
        }

        public void warn(int line, String construct, String message) {
            problems.add(new Problem(line, construct, message, Severity.WARNING));
        }

        /** A feature the runtime doesn't support yet: an error, or a warning in lenient mode. */
        public void unsupported(int line, String construct, String message) {
            problems.add(new Problem(line, construct, message, context.lenient ? Severity.WARNING : Severity.ERROR));
        }
    }

    public List<Problem> validate(LoomScript script, Context context) {
        Checker c = new Checker(script, context);
        checkAgents(c);
        checkRouting(c);
        checkStatements(c);
        for (Consumer<Checker> extra : context.extraChecks) extra.accept(c);
        return c.problems;
    }

    /** Throws {@link LoomLoadException} if there are errors; returns the warnings. */
    public List<Problem> validateOrThrow(LoomScript script, Context context) {
        List<Problem> all = validate(script, context);
        List<Problem> errors = all.stream().filter(p -> p.severity() == Severity.ERROR).toList();
        if (!errors.isEmpty()) throw new LoomLoadException(errors);
        return all;
    }

    // ── checks ───────────────────────────────────────────────────────────────────────────────

    private void checkAgents(Checker c) {
        LoomScript s = c.script();
        for (AgentDef a : s.getAgents()) {
            String who = "agent " + a.getName();
            if (a.getMemory() != null) {
                c.unsupported(a.getMemory().getLine() > 0 ? a.getMemory().getLine() : a.getLine(), who,
                        "agent memory is not supported yet (workflow context comes from Loom's memory engine); "
                                + "remove the memory block, or run with --lenient to ignore it");
            }
            for (String kb : a.getKnowledgeBases()) {
                if (s.getKnowledgeBases().stream().noneMatch(k -> k.getName().equals(kb))) {
                    c.error(a.getLine(), who, "knowledge " + kb + " is not defined (add: knowledge " + kb + " { source: \"…\" })");
                }
            }
            if (a.getRoutingPolicy() != null
                    && s.getRoutingPolicies().stream().noneMatch(r -> r.getName().equals(a.getRoutingPolicy()))) {
                c.error(a.getLine(), who, "routing policy " + a.getRoutingPolicy() + " is not defined");
            }
            for (String mcp : a.getMcpServers()) {
                if (s.getMcpServers().stream().noneMatch(m -> m.getName().equals(mcp))) {
                    c.error(a.getLine(), who, "mcp server " + mcp + " is not defined");
                }
            }
            for (String tool : a.getTools()) {
                if (!c.context().registeredTools.contains(tool)) {
                    c.error(a.getLine(), who, "tool " + tool + " is not defined: declare it (tool " + tool
                            + " { use: … }), use a built-in, map it in a .loot file, or register it from Java");
                }
            }
            for (String uri : a.getSkills()) {
                try {
                    loadSkill(uri);
                } catch (Exception e) {
                    c.error(a.getLine(), who, "skill " + uri + " can't be loaded: " + e.getMessage());
                }
            }
        }
    }

    private void checkRouting(Checker c) {
        for (RoutingPolicyDef r : c.script().getRoutingPolicies()) {
            if (r.getStrategy() != null && routingStrategy(r.getStrategy()) == null) {
                c.error(r.getLine(), "routing " + r.getName(), "unknown strategy \"" + r.getStrategy()
                        + "\"; use cost_aware or fallback");
            }
            if (r.getPrimaryModel() == null) c.error(r.getLine(), "routing " + r.getName(), "needs primary: \"<model>\"");
        }
        for (McpServerDef m : c.script().getMcpServers()) {
            if (m.getCmd() == null || m.getCmd().isBlank()) c.error(m.getLine(), "mcp " + m.getName(), "needs cmd: \"<command>\"");
        }
    }

    private void checkStatements(Checker c) {
        for (WorkflowDef w : c.script().getWorkflows()) walk(w.getStatements(), c);
    }

    private void walk(List<Statement> statements, Checker c) {
        if (statements == null) return;
        for (Statement st : statements) {
            if (st instanceof GuardrailStmt g) {
                if (!"PII".equalsIgnoreCase(g.getType())) {
                    c.unsupported(g.getLine(), "guardrail " + g.getType(), "unknown guardrail type; supported: PII");
                }
                walk(g.getBody(), c);
                walk(g.getOnViolation(), c);
            } else if (st instanceof DelegateStmt d) {
                walk(d.getOnFailure(), c);
            } else if (st instanceof LoopStmt l) {
                walk(l.getBody(), c);
                walk(l.getOnExhausted(), c);
            } else if (st instanceof ForEachStmt f) {
                walk(f.getBody(), c);
                walk(f.getOnExhausted(), c);
            } else if (st instanceof AltStmt a) {
                walk(a.getIfBranch(), c);
                walk(a.getElseBranch(), c);
            } else if (st instanceof ParallelStmt p) {
                walk(p.getBody(), c);
            }
        }
    }

    // ── shared helpers (also used by the executor) ──────────────────────────────────────────

    /** {@code cost_aware} or {@code fallback} (any case, '-' or '_'), else null. */
    public static String routingStrategy(String written) {
        String s = written.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return s.equals("cost_aware") || s.equals("fallback") ? s : null;
    }

    public static AgentSkill loadSkill(String uri) throws Exception {
        if (uri.startsWith("classpath://")) return AgentSkill.fromClasspath(uri.substring(12));
        String path = uri.startsWith("fs://") ? uri.substring(5) : uri;
        return new FileSystemSkillLoader().load(path);
    }
}
