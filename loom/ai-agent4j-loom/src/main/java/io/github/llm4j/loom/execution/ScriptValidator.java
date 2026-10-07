package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.skill.AgentSkill;
import io.github.llm4j.agent.skill.FileSystemSkillLoader;
import io.github.llm4j.loom.ast.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        io.github.llm4j.agent.task.TaskRegistry tasks = new io.github.llm4j.agent.task.TaskRegistry();
        Function<String, String> env = System::getenv;
        boolean lenient;
        boolean humanInterface;
        java.nio.file.Path baseDir = java.nio.file.Path.of("").toAbsolutePath();
        io.github.llm4j.loom.prompt.PromptCatalog prompts;
        final List<Consumer<Checker>> extraChecks = new ArrayList<>();

        public Context registeredTools(Set<String> names) {
            this.registeredTools = Set.copyOf(names);
            return this;
        }

        /** The tasks {@code run} statements may name. */
        public Context tasks(io.github.llm4j.agent.task.TaskRegistry tasks) {
            this.tasks = tasks;
            return this;
        }

        public io.github.llm4j.agent.task.TaskRegistry tasks() {
            return tasks;
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

        /** Where relative paths (fs:// skills) are resolved. */
        public Context baseDir(java.nio.file.Path dir) {
            this.baseDir = dir;
            return this;
        }

        /** The prompts the script's {@code prompt:} references resolve against; null when there is no catalog. */
        public Context prompts(io.github.llm4j.loom.prompt.PromptCatalog prompts) {
            this.prompts = prompts;
            return this;
        }

        /** The tool names the script can use: declared, built in, or registered by the host. */
        public Set<String> registeredTools() {
            return registeredTools;
        }

        public boolean hasHumanInterface() {
            return humanInterface;
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
        checkPromptFiles(c);
        checkWorkflowLint(c);
        checkRouting(c);
        checkStatements(c);
        for (Consumer<Checker> extra : context.extraChecks) extra.accept(c);
        List<Problem> sorted = new ArrayList<>(c.problems);
        sorted.sort(java.util.Comparator.comparingInt(Problem::line)); // stable: same-line problems keep their order
        return sorted;
    }

    /** Throws {@link LoomLoadException} if there are errors; returns the warnings. */
    public List<Problem> validateOrThrow(LoomScript script, Context context) {
        List<Problem> all = validate(script, context);
        List<Problem> errors = all.stream().filter(p -> p.severity() == Severity.ERROR).toList();
        if (!errors.isEmpty()) throw new LoomLoadException(errors);
        return all;
    }

    // ── checks ───────────────────────────────────────────────────────────────────────────────

    /** Warnings about what a workflow stores and never reads (see {@link WorkflowLint}). */
    private void checkWorkflowLint(Checker c) {
        for (io.github.llm4j.loom.ast.WorkflowDef workflow : c.script().getWorkflows()) {
            for (WorkflowLint.Finding f : WorkflowLint.check(workflow)) c.warn(f.line(), f.construct(), f.message());
        }
    }

    /** What is wrong with the prompt folder itself: files refused or ignored, and files no agent uses. */
    private void checkPromptFiles(Checker c) {
        var catalog = c.context().prompts;
        if (catalog == null || catalog.folder().isEmpty()) return;
        var folder = catalog.folder().get();
        int line = c.script().getPromptsDirLine();
        for (var problem : folder.problems()) {
            String text = problem.file().getFileName() + ": " + problem.message();
            if (problem.message().startsWith("ignored")) c.warn(line, "prompt files", text);
            else c.error(line, "prompt files", text);
        }
        java.util.Set<String> used = new java.util.HashSet<>();
        for (AgentDef a : c.script().getAgents()) {
            if (a.getPromptRef() == null) continue;
            try {
                used.add(io.github.llm4j.loom.prompt.PromptRef.parse(a.getPromptRef()).id());
            } catch (IllegalArgumentException e) {
                // a bad reference is reported by the parser
            }
        }
        for (String id : folder.ids()) {
            if (!used.contains(id)) {
                c.warn(line, "prompt files", "prompt " + id + " is not used by any agent (" + folder.root().relativize(folder.latest(id).orElseThrow().file()) + ")");
            }
        }
    }

    private void checkAgents(Checker c) {
        LoomScript s = c.script();
        for (AgentDef a : s.getAgents()) {
            String who = "agent " + a.getName();
            if (a.getPersona() != null && s.getPersonas().stream().noneMatch(p -> p.getName().equals(a.getPersona()))
                    && libraryPersona(a.getPersona()) == null) {
                c.error(a.getLine(), who, "persona " + a.getPersona() + " is not defined: declare it (persona "
                        + a.getPersona() + " { role: \"…\" }) or use a built-in one " + LIBRARY_PERSONAS);
            }
            String ref = a.getPromptRef();
            if (ref != null) {
                String attr = a.getPrompt() != null ? "prompt" : "system_template";
                if (c.context().prompts == null) {
                    c.error(a.getLine(), who, attr + " " + ref + " needs a prompt registry or prompt files: create a prompts/ folder next to the script, name one with prompts: \"./dir\" or --prompts, or use system: \"…\"");
                } else {
                    String problem = c.context().prompts.problemWith(ref, attr);
                    if (problem != null) c.error(a.getLine(), who, problem);
                }
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
                    loadSkill(uri, c.context().baseDir);
                } catch (Exception e) {
                    c.error(a.getLine(), who, "skill " + uri + " can't be loaded: " + e.getMessage());
                }
            }
        }
    }

    /** Names of {@code PersonaLibrary} personas usable as {@code persona: name}. */
    public static final java.util.SortedSet<String> LIBRARY_PERSONAS = new java.util.TreeSet<>();

    static {
        for (java.lang.reflect.Method m : io.github.llm4j.agent.persona.PersonaLibrary.class.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                    && m.getReturnType() == io.github.llm4j.agent.persona.AgentPersona.class) {
                LIBRARY_PERSONAS.add(m.getName());
            }
        }
    }

    /** A {@code PersonaLibrary} persona by method name, or null. */
    public static io.github.llm4j.agent.persona.AgentPersona libraryPersona(String name) {
        if (!LIBRARY_PERSONAS.contains(name)) return null;
        try {
            return (io.github.llm4j.agent.persona.AgentPersona) io.github.llm4j.agent.persona.PersonaLibrary.class.getMethod(name).invoke(null);
        } catch (ReflectiveOperationException e) {
            return null;
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
        TaskChecks.run(c);
        RewindChecks.run(c);
        DecisionChecks.run(c);
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
                knownAgent(c, d.getLine(), "delegate", d.getTargetAgent());
                walk(d.getOnFailure(), c);
            } else if (st instanceof RunStmt r) {
                walk(r.getOnFailure(), c);
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
            } else if (st instanceof io.github.llm4j.loom.ast.BroadcastStmt b) {
                b.getTargetAgents().forEach(a -> knownAgent(c, b.getLine(), "broadcast", a));
            }
        }
    }

    /** A delegate or broadcast that names an agent the script does not define fails mid-run (a handoff may end a path with a name that is no agent, so it is not checked), after the earlier steps have spent money; say so before the run. */
    private void knownAgent(Checker c, int line, String step, String agent) {
        if (agent == null || agent.isBlank() || agent.startsWith("{")) return; // chosen by the data at run time
        List<String> known = c.script().getAgents().stream().map(a -> a.getName()).toList();
        if (known.contains(agent)) return;
        List<String> near = io.github.llm4j.loom.prompt.PromptCatalog.nearest(agent, known);
        c.error(line, step + " " + agent, "there is no agent named " + agent + (near.isEmpty() ? "" : "; did you mean " + String.join(", ", near) + "?")
                + " Agents defined: " + (known.isEmpty() ? "none" : String.join(", ", known)) + ".");
    }

    // ── shared helpers (also used by the executor) ──────────────────────────────────────────

    /** {@code cost_aware} or {@code fallback} (any case, '-' or '_'), else null. */
    public static String routingStrategy(String written) {
        String s = written.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return s.equals("cost_aware") || s.equals("fallback") ? s : null;
    }

    private static final Map<String, AgentSkill> REMOTE_SKILLS = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AgentSkill> eldest) {
                    return size() > 256;
                }
            });

    /**
     * {@code classpath://…}, {@code https://…} (or {@code http://} on localhost), or a file ({@code fs://…}
     * or a plain path) relative to {@code baseDir}. Remote skills are fetched once per process.
     */
    public static AgentSkill loadSkill(String uri, java.nio.file.Path baseDir) throws Exception {
        if (uri.startsWith("classpath://")) return AgentSkill.fromClasspath(uri.substring(12));
        String lower = uri.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            java.net.URI parsed = java.net.URI.create(uri);
            String host = parsed.getHost() == null ? "" : parsed.getHost();
            if (lower.startsWith("http://") && !host.equals("localhost") && !host.equals("127.0.0.1")) {
                throw new IllegalArgumentException("remote skills must use https:// (http:// only for localhost)");
            }
            AgentSkill cached = REMOTE_SKILLS.get(uri);
            if (cached != null) return cached;
            AgentSkill skill = new io.github.llm4j.agent.skill.RemoteSkillLoader(java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10)).followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build())
                    .load(uri);
            REMOTE_SKILLS.put(uri, skill);
            return skill;
        }
        java.nio.file.Path path = java.nio.file.Path.of(uri.startsWith("fs://") ? uri.substring(5) : uri);
        if (!path.isAbsolute()) path = baseDir.resolve(path);
        return new FileSystemSkillLoader().load(path.toString());
    }
}
