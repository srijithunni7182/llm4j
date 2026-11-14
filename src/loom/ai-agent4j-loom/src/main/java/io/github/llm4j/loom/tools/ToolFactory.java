package io.github.llm4j.loom.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.Effectful;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.agent.tools.CalculatorTool;
import io.github.llm4j.agent.tools.CurrentTimeTool;
import io.github.llm4j.agent.tools.DateTimeTool;
import io.github.llm4j.agent.tools.DuckDuckGoSearchTool;
import io.github.llm4j.agent.tools.SerpApiSearchTool;
import io.github.llm4j.agent.tools.WebSearchTool;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.secret.CredentialSource;
import io.github.llm4j.tools.DescribedTool;
import io.github.llm4j.tools.EffectTool;
import io.github.llm4j.tools.Options;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import okhttp3.OkHttpClient;

/**
 * Builds the tools a script declares ({@code tool Name { use: kind … }}) and the built-in tools
 * usable by name ({@code web_search}, {@code calculator}, {@code datetime}, {@code current_time}).
 * Hosts can add kinds with {@link #register}.
 */
public final class ToolFactory {

    /** Built-in names and the declaration each stands for. */
    public static final Map<String, String> BUILT_INS =
            Map.of(
                    "web_search", "duckduckgo",
                    "calculator", "calculator",
                    "datetime", "datetime",
                    "current_time", "current_time",
                    "translate", "translate",
                    "transliterate", "transliterate",
                    "detect_language", "detect_language",
                    "speak", "speak",
                    "transcribe", "transcribe");

    /** The option every kind accepts: text added to what the model is told about the tool. */
    static final String DESCRIPTION = "description";

    /**
     * What your own (class) tool reaches, for {@code weave audit}: none, reads, fetches, writes or
     * sends. The audit never loads your code, so it reads this.
     */
    public static final String REACH = "reach";

    public static final Set<String> REACHES = Set.of("none", "reads", "fetches", "writes", "sends");

    /**
     * {@code replay: allow} lets a tool run when a past case is replayed; it is the runtime's
     * option, not the tool's.
     */
    static final String REPLAY = "replay";

    /** Where a built-in kind comes from, in {@link #sources()}. */
    public static final String BUILT_IN = "built in";

    private final Map<String, ToolKind> kinds = new LinkedHashMap<>();
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final List<String> loadProblems = new java.util.ArrayList<>();
    private Function<String, ToolDef> declarations = name -> null;

    /**
     * How this factory finds the other tools of the script, for a kind whose option names one
     * ({@link ToolKind#references()}); without it no tool is found.
     */
    public ToolFactory declarations(Function<String, ToolDef> declarations) {
        this.declarations = java.util.Objects.requireNonNull(declarations, "declarations");
        return this;
    }

    /**
     * A factory that knows the built-in kinds and the kinds the jars on the project's class path
     * bring ({@link io.github.llm4j.loom.init.ProjectClasses#loader()}, which {@code --classes}
     * sets).
     */
    public ToolFactory() {
        this(io.github.llm4j.loom.init.ProjectClasses.loader());
    }

    /**
     * A factory that knows the built-in kinds ({@link #standardKinds()}) and the kinds the jars
     * visible to {@code loader} list in {@code
     * META-INF/services/io.github.llm4j.agent.tool.ToolKind}. A name is registered once: a jar that
     * brings a name already known, or a kind that fails to load, is left out and reported in {@link
     * #loadProblems()}.
     */
    public ToolFactory(ClassLoader loader) {
        standardKinds().forEach(k -> add(k, BUILT_IN));
        DiscoveredKinds found = DiscoveredKinds.in(loader);
        loadProblems.addAll(found.problems());
        for (DiscoveredKinds.Found f : found.kinds()) {
            String clash = clash(f.kind().name(), f.source());
            if (clash == null) add(f.kind(), f.source());
            else loadProblems.add(clash);
        }
    }

    /**
     * The kinds a script can name with no host code: one new instance of each, in the order they
     * are listed. The one place the built-in kinds are listed (D3); what each reaches is its own
     * {@link ToolKind#profile} (D2).
     */
    static java.util.List<ToolKind> standardKinds() {
        java.util.List<ToolKind> out = new java.util.ArrayList<>();
        java.util.function.Consumer<ToolKind> register = out::add;

        register.accept(
                simple(
                        "duckduckgo",
                        o -> SEARCH_RESULTS,
                        Set.of(),
                        Set.of("base_url"),
                        Set.of(),
                        (n, o, dir) ->
                                o.containsKey("base_url")
                                        ? new DuckDuckGoSearchTool(
                                                new OkHttpClient(), o.get("base_url"))
                                        : new DuckDuckGoSearchTool()));
        register.accept(
                simple(
                        "serpapi",
                        o -> SEARCH_RESULTS,
                        Set.of("api_key"),
                        Set.of("base_url"),
                        Set.of("api_key"),
                        (n, o, dir) ->
                                o.containsKey("base_url")
                                        ? new SerpApiSearchTool(
                                                o.get("api_key"),
                                                new OkHttpClient(),
                                                o.get("base_url"))
                                        : new SerpApiSearchTool(o.get("api_key"))));
        register.accept(
                simple(
                        "google_search",
                        o -> SEARCH_RESULTS,
                        Set.of("api_key", "cx"),
                        Set.of(),
                        Set.of("api_key"),
                        (n, o, dir) -> new WebSearchTool(o.get("api_key"), o.get("cx"))));
        register.accept(
                simple(
                        "calculator",
                        o -> ToolProfile.of(false, false, false, false, "calculator"),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        (n, o, dir) -> new CalculatorTool()));
        register.accept(
                simple(
                        "datetime",
                        o -> ToolProfile.of(false, false, false, false, "datetime"),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        (n, o, dir) -> new DateTimeTool()));
        register.accept(
                simple(
                        "current_time",
                        o -> ToolProfile.of(false, false, false, false, "current_time"),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        (n, o, dir) -> new CurrentTimeTool()));
        register.accept(
                simple(
                        "class",
                        o -> ReachDeclaration.profile(o.get(REACH), "Java class " + o.get("class")),
                        Set.of("class"),
                        Set.of(),
                        Set.of(),
                        (n, o, dir) -> {
                            Class<?> c =
                                    Class.forName(
                                            o.get("class"),
                                            true,
                                            io.github.llm4j.loom.init.ProjectClasses.loader());
                            if (!Tool.class.isAssignableFrom(c)) {
                                throw new IllegalArgumentException(
                                        o.get("class")
                                                + " does not implement io.github.llm4j.agent.Tool");
                            }
                            return (Tool) c.getDeclaredConstructor().newInstance();
                        }));
        LanguageTools.registerAll(register);
        register.accept(new GenericKindAdapter(new io.github.llm4j.tools.WebhookKind()));
        register.accept(new GenericKindAdapter(new io.github.llm4j.tools.FileKind()));
        register.accept(new GenericKindAdapter(new io.github.llm4j.tools.HttpKind()));
        register.accept(new GenericKindAdapter(new io.github.llm4j.tools.EmailKind()));
        register.accept(new GenericKindAdapter(new io.github.llm4j.tools.ShellKind()));
        register.accept(new GenericKindAdapter(new io.github.llm4j.tools.SqlKind()));
        register.accept(new GenericKindAdapter(new io.github.llm4j.tools.ComputeKind()));
        register.accept(new GenericKindAdapter(new io.github.llm4j.tools.VerifyKind()));
        register.accept(new GenericKindAdapter(new io.github.llm4j.tools.ScanKind()));
        register.accept(new GenericKindAdapter(new io.github.llm4j.tools.WorkspaceKind()));
        register.accept(new GenericKindAdapter(new io.github.llm4j.tools.SandboxKind()));
        register.accept(new GraphKind());
        register.accept(
                simple(
                        "skill_registry",
                        o ->
                                ToolProfile.of(
                                        true,
                                        false,
                                        false,
                                        false,
                                        "skills fetched from " + o.get("url")),
                        Set.of("url"),
                        Set.of("api_key"),
                        Set.of("api_key"),
                        (n, o, dir) -> {
                            io.github.llm4j.agent.skill.RestSkillRegistry.Builder b =
                                    io.github.llm4j.agent.skill.RestSkillRegistry.builder()
                                            .baseUrl(o.get("url"));
                            if (o.containsKey("api_key")) b.apiKey(o.get("api_key"));
                            return new io.github.llm4j.agent.tool.SkillDiscoveryTool(b.build());
                        }));
        return out;
    }

    /** What a search kind's results are: content from the web, nothing else. */
    /**
     * The kinds of the optional extras modules (phase 35, D4), by the module that brings each: only
     * so a script that uses one without its jar is told which module to add (G39). Loom depends on
     * none of them.
     */
    static final Map<String, String> EXTRAS =
            Map.of(
                    "search", "ai-agent4j-tools-search",
                    "documents", "ai-agent4j-tools-documents",
                    "git", "ai-agent4j-tools-git",
                    "browser", "ai-agent4j-tools-browser",
                    "openapi", "ai-agent4j-tools-openapi");

    private static final ToolProfile SEARCH_RESULTS =
            ToolProfile.of(true, false, false, false, "web search results");

    /** The built-in kinds by name, made on first use. */
    private static final class Standard {
        static final Map<String, ToolKind> KINDS = byName(standardKinds());
    }

    private static Map<String, ToolKind> byName(List<ToolKind> list) {
        Map<String, ToolKind> out = new LinkedHashMap<>();
        list.forEach(k -> out.put(k.name(), k));
        return java.util.Collections.unmodifiableMap(out);
    }

    /** The kinds the jars visible to the last loader asked about bring, found once per loader. */
    private static final java.util.concurrent.atomic.AtomicReference<
                    Map.Entry<ClassLoader, Map<String, ToolKind>>>
            DISCOVERED = new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * A kind a script can name with no host code, for asking what it reaches: a built-in, else one
     * a jar on the project's class path brings ({@link
     * io.github.llm4j.loom.init.ProjectClasses#loader()}). A jar's kind with a built-in's name is
     * not one: the built-in wins, and {@code weave check} reports the jar.
     */
    public static java.util.Optional<ToolKind> knownKind(String name) {
        java.util.Optional<ToolKind> builtIn = standardKind(name);
        if (builtIn.isPresent()) return builtIn;
        ClassLoader loader = io.github.llm4j.loom.init.ProjectClasses.loader();
        Map.Entry<ClassLoader, Map<String, ToolKind>> last = DISCOVERED.get();
        if (last == null || last.getKey() != loader) {
            Map<String, ToolKind> found = new LinkedHashMap<>();
            DiscoveredKinds.in(loader)
                    .kinds()
                    .forEach(f -> found.putIfAbsent(f.kind().name(), f.kind()));
            last = Map.entry(loader, java.util.Collections.unmodifiableMap(found));
            DISCOVERED.set(last);
        }
        return java.util.Optional.ofNullable(last.getValue().get(name));
    }

    /** The names of the built-in kinds, sorted. */
    public static Set<String> standardKindNames() {
        return java.util.Collections.unmodifiableSet(new TreeSet<>(Standard.KINDS.keySet()));
    }

    /**
     * The built-in kind of this name, for asking what it reaches ({@link ToolKind#profile}). Never
     * used to build a tool: a tool is built by a factory, which holds its own kinds.
     */
    public static java.util.Optional<ToolKind> standardKind(String name) {
        return java.util.Optional.ofNullable(Standard.KINDS.get(name));
    }

    @FunctionalInterface
    interface Creator {
        Tool create(String name, Map<String, String> options, Path baseDir) throws Exception;
    }

    private static ToolKind simple(
            String kind,
            Function<Map<String, String>, ToolProfile> profile,
            Set<String> required,
            Set<String> optional,
            Set<String> secrets,
            Creator creator) {
        return new ToolKind() {
            @Override
            public String name() {
                return kind;
            }

            @Override
            public Set<String> required() {
                return required;
            }

            @Override
            public Set<String> optional() {
                return optional;
            }

            @Override
            public Set<String> secrets() {
                return secrets;
            }

            @Override
            public ToolProfile profile(Map<String, String> options) {
                return profile.apply(options);
            }

            @Override
            public Tool create(String name, Map<String, String> options, Path baseDir)
                    throws Exception {
                return creator.create(name, options, baseDir);
            }
        };
    }

    /**
     * Adds a kind the host supplies.
     *
     * @throws IllegalArgumentException when a kind of that name is already registered: a name is
     *     registered once, so nothing can quietly replace {@code file} or {@code shell}
     */
    public ToolFactory register(ToolKind kind) {
        String source = "the host (" + kind.getClass().getName() + ")";
        String clash = clash(kind.name(), source);
        if (clash != null) throw new IllegalArgumentException(clash);
        add(kind, source);
        return this;
    }

    private void add(ToolKind kind, String source) {
        kinds.put(kind.name(), kind);
        sources.put(kind.name(), source);
    }

    /** Why a kind of this name from {@code source} cannot be added, or null. */
    private String clash(String name, String source) {
        String existing = sources.get(name);
        return existing == null
                ? null
                : "tool kind \""
                        + name
                        + "\" from "
                        + source
                        + " has the name of the one from "
                        + existing
                        + "; a name is registered once, so it was not loaded (remove one of them)";
    }

    /**
     * Where each kind came from, by name: {@link #BUILT_IN}, the jar or folder that listed it, or
     * the host.
     */
    public Map<String, String> sources() {
        return java.util.Collections.unmodifiableMap(sources);
    }

    /** The kinds that came from jars or folders on the class path, with where each came from. */
    public Map<String, String> jarKinds() {
        Map<String, String> out = new java.util.TreeMap<>();
        sources.forEach(
                (name, source) -> {
                    if (!source.equals(BUILT_IN) && !source.startsWith("the host ("))
                        out.put(name, source);
                });
        return out;
    }

    /**
     * The kinds that could not be added: one that failed to load, or a name already registered.
     * Each names where it came from; {@code weave check} reports them as errors.
     */
    public List<String> loadProblems() {
        return List.copyOf(loadProblems);
    }

    /**
     * The fields of a result of this kind that may hold model text ({@link
     * ToolKind#uncheckedResult}); a kind this factory does not have: the whole result.
     */
    public Set<String> uncheckedResultOf(String kind, Map<String, String> options) {
        ToolKind k = kinds.get(kind);
        return k == null
                ? Set.of(io.github.llm4j.agent.tool.ToolKind.WHOLE_RESULT)
                : k.uncheckedResult(options);
    }

    /**
     * What a tool of this kind with these options (as written) reaches; a kind this factory does
     * not have is assumed to reach everything.
     */
    public ToolProfile profileOf(String kind, Map<String, String> options) {
        ToolKind k = kinds.get(kind);
        return k == null
                ? ToolProfile.unknown("tool kind \"" + kind + "\" is not one the audit knows")
                : k.profile(options);
    }

    public Set<String> kinds() {
        return new TreeSet<>(kinds.keySet());
    }

    /** The declaration a built-in name stands for, or null. */
    public static ToolDef builtIn(String name) {
        String kind = BUILT_INS.get(name);
        if (kind == null) return null;
        ToolDef d = new ToolDef(name);
        d.setKind(kind);
        return d;
    }

    /**
     * Everything wrong with a declaration, as messages (no secret values in them). Nothing is
     * created or contacted.
     */
    public List<String> problems(ToolDef def, Function<String, String> env) {
        return problems(def, env, Path.of("").toAbsolutePath());
    }

    /** As {@link #problems(ToolDef, Function)}, resolving files against the script's directory. */
    public List<String> problems(ToolDef def, Function<String, String> env, Path baseDir) {
        List<String> out = new java.util.ArrayList<>();
        ToolKind kind = kinds.get(def.getKind());
        if (kind == null) {
            String module = EXTRAS.get(def.getKind());
            out.add(
                    module != null
                            ? "the tool kind '"
                                    + def.getKind()
                                    + "' comes in the optional module "
                                    + module
                                    + ", which is not on the class path: add the dependency"
                                    + " io.github.srijithunni7182:"
                                    + module
                                    + " to your project, or give weave its jar (weave --classes "
                                    + module
                                    + "-<version>-weave.jar, or the plain jar for a module with no"
                                    + " dependency of its own)"
                            : "unknown tool kind '"
                                    + def.getKind()
                                    + "'; use one of "
                                    + kinds()
                                    + " (a kind from an optional module needs its jar: weave --classes"
                                    + " <jar>, or a dependency of your project)");
            return out;
        }
        for (String req : kind.required()) {
            if (def.getOptions().containsKey(req)) continue;
            ToolDef.OptionValue fallback = kind.defaults().get(req);
            if (fallback == null) {
                out.add("use: " + kind.name() + " needs " + req + ":");
            } else if (fallback.isReference() && isUnset(env.apply(fallback.lookupKey()))) {
                out.add(
                        fallback.describe()
                                + " is not set (for "
                                + req
                                + "; or give "
                                + req
                                + ": env.<NAME> or secret.<NAME>)");
            }
        }
        for (Map.Entry<String, String> ref : kind.references().entrySet()) {
            ToolDef.OptionValue named = def.getOptions().get(ref.getKey());
            if (named == null) continue;
            ToolDef target = declarations.apply(named.value());
            if (target == null)
                out.add(ref.getKey() + ": names no tool " + named.value() + " (declare tool " + named.value() + " { use: " + ref.getValue() + " ... })");
            else if (!ref.getValue().equals(target.getKind()))
                out.add(ref.getKey() + ": " + named.value() + " is a " + target.getKind() + " tool, not a " + ref.getValue() + " tool");
        }
        for (Map.Entry<String, ToolDef.OptionValue> e : def.getOptions().entrySet()) {
            String key = e.getKey();
            if (key.equals(DESCRIPTION)) continue;
            String filled = filledFrom(kind, key);
            if (filled != null) {
                out.add(key + " is taken from the tool " + filled + ": names; don't write it here");
                continue;
            }
            if (key.equals(REACH)) {
                if (!kind.name().equals("class"))
                    out.add(
                            "reach: is for your own tools (use: class); "
                                    + kind.name()
                                    + " already tells the audit what it reaches");
                else if (!REACHES.contains(e.getValue().value()))
                    out.add(
                            "reach: must be one of "
                                    + String.join(", ", new java.util.TreeSet<>(REACHES))
                                    + ", not "
                                    + e.getValue().value());
                continue;
            }
            if (key.equals(REPLAY)) {
                if (!e.getValue().value().equals("allow")
                        && !e.getValue().value().equals("simulate"))
                    out.add("replay: must be allow or simulate, not " + e.getValue().value());
                continue;
            }
            String prefix = prefixOf(kind, key);
            if (prefix != null && kind.resolvedPerCall().contains(prefix) && !e.getValue().isReference()) {
                out.add(
                        key
                                + " must name a secret or a variable (secret.NAME or env.NAME): the tool looks it up on each call and never holds the value");
                continue;
            }
            if (prefix != null) {
                String problem = checkPrefixed(prefix, key, e.getValue(), env);
                if (problem != null) out.add(problem);
                continue;
            }
            if (!kind.required().contains(key) && !kind.optional().contains(key)) {
                out.add(
                        "unknown option "
                                + key
                                + " for use: "
                                + kind.name()
                                + (kind.required().isEmpty() && kind.optional().isEmpty()
                                        ? " (it takes none)"
                                        : "; it takes "
                                                + new TreeSet<>(
                                                        union(kind.required(), kind.optional()))
                                                + (kind.prefixes().isEmpty()
                                                        ? ""
                                                        : " and " + kind.prefixes() + "<name>")));
                continue;
            }
            if (e.getValue().fromInvoker()) {
                if (!kind.lateBound().contains(key))
                    out.add(notInvokerOption(kind, key, e.getValue()));
                continue;
            }
            if (kind.secrets().contains(key) && !e.getValue().isReference()) {
                out.add(
                        key
                                + " must come from the environment or the secret store, e.g. "
                                + key
                                + ": secret."
                                + suggestEnv(kind.name(), def.getName(), key)
                                + " (or env."
                                + suggestEnv(kind.name(), def.getName(), key)
                                + ")"
                                + (kind.lateBound().contains(key)
                                        ? ", or invoker.NAME for the credential of the person running the workflow"
                                        : ""));
                continue;
            }
            if (e.getValue().isReference()) {
                String v = env.apply(e.getValue().lookupKey());
                if (v == null || v.isEmpty()) out.add(notSet(e.getValue()) + " (for " + key + ")");
            }
        }
        if (out.isEmpty()) {
            String extra = kind.check(resolve(kind, def, env), baseDir);
            if (extra != null) out.add(extra);
        }
        return out;
    }

    /**
     * What is wrong with an agent using this declared tool, given whether the agent has it under
     * {@code approve:}.
     */
    public String agentProblem(
            ToolDef def, Function<String, String> env, String agentName, boolean approved) {
        ToolKind kind = kinds.get(def.getKind());
        return kind == null
                ? null
                : kind.agentProblem(resolve(kind, def, env), def.getName(), agentName, approved);
    }

    /** Whether a tool of {@code kindName} is built when a run starts (an unknown kind is not). */
    public boolean buildsAtRunStart(String kindName) {
        ToolKind kind = kinds.get(kindName);
        return kind != null && kind.buildsAtRunStart();
    }

    /** The option a key is filled from when it is {@code <reference>.<name>}, else null. */
    private static String filledFrom(ToolKind kind, String key) {
        for (String ref : kind.references().keySet()) if (key.startsWith(ref + ".")) return ref;
        return null;
    }

    private static String prefixOf(ToolKind kind, String key) {
        for (String p : kind.prefixes())
            if (key.startsWith(p) && key.length() > p.length()) return p;
        return null;
    }

    /**
     * A {@code header.<Name>} option: a credential-looking header must come from the environment.
     */
    private static String checkPrefixed(
            String prefix, String key, ToolDef.OptionValue value, Function<String, String> env) {
        if (value.fromInvoker())
            return key + " can't come from " + value + " (not yet); use env.NAME or secret.NAME";
        if (prefix.equals(Options.HEADER_PREFIX)
                && Options.isSecretHeader(key.substring(prefix.length()))
                && !value.isReference()) {
            String suggested =
                    key.substring(prefix.length())
                            .toUpperCase(java.util.Locale.ROOT)
                            .replaceAll("[^A-Z0-9]+", "_");
            return key
                    + " must come from the environment or the secret store, e.g. "
                    + key
                    + ": secret."
                    + suggested
                    + " (or env."
                    + suggested
                    + ")";
        }
        if (value.isReference() && isUnset(env.apply(value.lookupKey()))) {
            return notSet(value) + " (for " + key + ")";
        }
        return null;
    }

    /** Why an option can't be written {@code invoker.NAME}, and where it can. */
    private String notInvokerOption(ToolKind kind, String key, ToolDef.OptionValue value) {
        List<String> where = new java.util.ArrayList<>();
        kinds.values()
                .forEach(k -> k.lateBound().forEach(o -> where.add(o + " of use: " + k.name())));
        return key
                + " can't come from "
                + value
                + " for use: "
                + kind.name()
                + "; invoker credentials are for "
                + String.join(", ", where);
    }

    /** What to say when a reference resolves to nothing. */
    private static String notSet(ToolDef.OptionValue v) {
        return v.fromSecret()
                ? "secret "
                        + v.value()
                        + " is not in the secret store (give one with --secrets, or HarnessExecutor.setSecretStore)"
                : v.describe() + " is not set";
    }

    private static boolean isUnset(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Builds the tool, presented under its declared name. Call only when {@link #problems} is
     * empty.
     */
    public Tool create(ToolDef def, Function<String, String> env, Path baseDir) throws Exception {
        return create(def, env, baseDir, EffectContext.noop());
    }

    /**
     * As {@link #create(ToolDef, Function, Path)}, for a tool that runs inside a run: side-effect
     * tools are journaled through {@code context} so a resumed run doesn't repeat them.
     */
    public Tool create(
            ToolDef def, Function<String, String> env, Path baseDir, EffectContext context)
            throws Exception {
        return create(
                def,
                env,
                baseDir,
                context,
                name -> {
                    throw new IllegalStateException(
                            "tool "
                                    + def.getName()
                                    + " takes invoker."
                                    + name
                                    + ", which only a run can supply");
                });
    }

    /**
     * As above, with the way to get a {@link CredentialSource} for a credential of the person
     * running the workflow ({@code invoker.NAME} options). Nothing is asked of it until the tool
     * makes a request.
     */
    public Tool create(
            ToolDef def,
            Function<String, String> env,
            Path baseDir,
            EffectContext context,
            Function<String, CredentialSource> invoker)
            throws Exception {
        ToolKind kind = kinds.get(def.getKind());
        Map<String, String> options = resolve(kind, def, env);
        Map<String, CredentialSource> lateBound = new LinkedHashMap<>();
        def.getOptions()
                .forEach(
                        (key, value) -> {
                            if (value.fromInvoker())
                                lateBound.put(key, invoker.apply(value.value()));
                        });
        lateBound.keySet().forEach(options::remove);
        // the effects of a tool that holds several operations, for a kind that takes them
        if (kind.optional().contains("effects") && !def.getOperationEffects().isEmpty()) {
            List<String> pairs = new java.util.ArrayList<>();
            def.getOperationEffects()
                    .forEach(
                            (operation, effect) ->
                                    pairs.add(
                                            operation
                                                    + "="
                                                    + effect.name().toLowerCase(java.util.Locale.ROOT)));
            options.put("effects", String.join(",", pairs));
        }
        String description = options.remove(DESCRIPTION);
        options.remove(REPLAY);
        options.remove(REACH);
        Tool tool = kind.create(def.getName(), options, lateBound, baseDir, context);
        if (tool instanceof Effectful effectful) tool = new EffectTool(effectful, context);
        if (description != null) tool = new DescribedTool(tool, description);
        return new NamedTool(def.getName(), tool);
    }

    private Map<String, String> resolve(ToolKind kind, ToolDef def, Function<String, String> env) {
        return resolve(kind, def, env, new java.util.HashSet<>());
    }

    /**
     * A tool this one names (workspace: Data) lends its options as workspace.root, ..., and so on
     * down: an http tool that reaches: a sandbox sees reaches.workspace.store. {@code seen} stops a
     * loop of tools that name each other.
     */
    private Map<String, String> resolve(
            ToolKind kind, ToolDef def, Function<String, String> env, Set<String> seen) {
        Map<String, String> out = values(kind, def, env);
        if (!seen.add(def.getName())) return out;
        for (Map.Entry<String, String> ref : kind.references().entrySet()) {
            ToolDef.OptionValue named = def.getOptions().get(ref.getKey());
            ToolDef target = named == null ? null : declarations.apply(named.value());
            if (target == null || !ref.getValue().equals(target.getKind())) continue;
            ToolKind targetKind = kinds.get(target.getKind());
            if (targetKind == null) continue;
            resolve(targetKind, target, env, seen)
                    .forEach((k, v) -> out.put(ref.getKey() + "." + k, v));
        }
        return out;
    }

    private static Map<String, String> values(ToolKind kind, ToolDef def, Function<String, String> env) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, ToolDef.OptionValue> all = new LinkedHashMap<>(kind.defaults());
        all.putAll(def.getOptions());
        all.forEach(
                (k, v) -> {
                    // an invoker.NAME has no value yet: its text stands in for it while the options
                    // are checked, and create() takes it out again. An option the kind resolves
                    // on each call keeps its reference too, and the tool looks it up when it runs.
                    String value =
                            v.fromInvoker() || (v.isReference() && perCall(kind, k))
                                    ? v.toString()
                                    : v.isReference() ? env.apply(v.lookupKey()) : v.value();
                    if (!isUnset(value)) out.put(k, value);
                });
        return out;
    }

    private static boolean perCall(ToolKind kind, String key) {
        for (String p : kind.resolvedPerCall()) if (key.startsWith(p)) return true;
        return false;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> s = new TreeSet<>(a);
        s.addAll(b);
        return s;
    }

    private static String suggestEnv(String kind, String tool, String key) {
        String base = key.equals("api_key") ? kind + "_key" : tool + "_" + key;
        return base.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }
}
