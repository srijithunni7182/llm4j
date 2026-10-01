package io.github.llm4j.loom.tools;

import io.github.llm4j.tools.SafePaths;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tools.CalculatorTool;
import io.github.llm4j.agent.tools.CurrentTimeTool;
import io.github.llm4j.agent.tools.DateTimeTool;
import io.github.llm4j.agent.tools.DuckDuckGoSearchTool;
import io.github.llm4j.agent.tools.SerpApiSearchTool;
import io.github.llm4j.agent.tools.WebSearchTool;
import io.github.llm4j.agent.tools.openapi.OpenAPIParser;
import io.github.llm4j.agent.tools.openapi.OpenAPITool;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.tools.DescribedTool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.tools.EffectTool;
import io.github.llm4j.agent.tool.Effectful;
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
 * Builds the tools a script declares ({@code tool Name { use: kind … }}) and the built-in tools usable by
 * name ({@code web_search}, {@code calculator}, {@code datetime}, {@code current_time}). Hosts can add
 * kinds with {@link #register}.
 */
public final class ToolFactory {

    /** Built-in names and the declaration each stands for. */
    public static final Map<String, String> BUILT_INS = Map.of(
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

    private final Map<String, ToolKind> kinds = new LinkedHashMap<>();

    public ToolFactory() {
        register(simple("duckduckgo", Set.of(), Set.of("base_url"), Set.of(), (n, o, dir) -> o.containsKey("base_url")
                ? new DuckDuckGoSearchTool(new OkHttpClient(), o.get("base_url")) : new DuckDuckGoSearchTool()));
        register(simple("serpapi", Set.of("api_key"), Set.of("base_url"), Set.of("api_key"), (n, o, dir) -> o.containsKey("base_url")
                ? new SerpApiSearchTool(o.get("api_key"), new OkHttpClient(), o.get("base_url"))
                : new SerpApiSearchTool(o.get("api_key"))));
        register(simple("google_search", Set.of("api_key", "cx"), Set.of(), Set.of("api_key"),
                (n, o, dir) -> new WebSearchTool(o.get("api_key"), o.get("cx"))));
        register(new ToolKind() {
            @Override
            public String name() {
                return "openapi";
            }

            @Override
            public Set<String> required() {
                return Set.of("spec");
            }

            @Override
            public Set<String> optional() {
                return Set.of("auth_header", "auth_query", "auth_value");
            }

            @Override
            public Set<String> secrets() {
                return Set.of("auth_value");
            }

            @Override
            public String check(Map<String, String> o) {
                boolean header = o.containsKey("auth_header");
                boolean query = o.containsKey("auth_query");
                if (header && query) return "use auth_header or auth_query, not both";
                if ((header || query) != o.containsKey("auth_value")) {
                    return "auth_value goes with exactly one of auth_header or auth_query";
                }
                return null;
            }

            @Override
            public Tool create(String name, Map<String, String> o, Path dir) {
                String spec = o.get("spec");
                String location = spec.matches("(?i)https?://.*") || Path.of(spec).isAbsolute()
                        ? spec : dir.resolve(spec).toString();
                OpenAPITool.Builder b = OpenAPITool.builder().name(name).spec(OpenAPIParser.parse(location));
                if (o.containsKey("auth_header")) b.headerAuth(o.get("auth_header"), o.get("auth_value"));
                if (o.containsKey("auth_query")) b.apiKeyAuth(o.get("auth_query"), o.get("auth_value"));
                return b.build();
            }
        });
        register(simple("calculator", Set.of(), Set.of(), Set.of(), (n, o, dir) -> new CalculatorTool()));
        register(simple("datetime", Set.of(), Set.of(), Set.of(), (n, o, dir) -> new DateTimeTool()));
        register(simple("current_time", Set.of(), Set.of(), Set.of(), (n, o, dir) -> new CurrentTimeTool()));
        register(simple("class", Set.of("class"), Set.of(), Set.of(), (n, o, dir) -> {
            Class<?> c = Class.forName(o.get("class"));
            if (!Tool.class.isAssignableFrom(c)) {
                throw new IllegalArgumentException(o.get("class") + " does not implement io.github.llm4j.agent.Tool");
            }
            return (Tool) c.getDeclaredConstructor().newInstance();
        }));
        LanguageTools.registerAll(this);
        register(new GenericKindAdapter(new io.github.llm4j.tools.WebhookKind()));
        register(new GenericKindAdapter(new io.github.llm4j.tools.FileKind()));
        register(new GenericKindAdapter(new io.github.llm4j.tools.HttpKind()));
        register(new GenericKindAdapter(new io.github.llm4j.tools.EmailKind()));
        register(new GenericKindAdapter(new io.github.llm4j.tools.ShellKind()));
        register(new GenericKindAdapter(new io.github.llm4j.tools.SqlKind()));
        register(new GraphKind());
        register(simple("skill_registry", Set.of("url"), Set.of("api_key"), Set.of("api_key"), (n, o, dir) -> {
            io.github.llm4j.agent.skill.RestSkillRegistry.Builder b = io.github.llm4j.agent.skill.RestSkillRegistry.builder().baseUrl(o.get("url"));
            if (o.containsKey("api_key")) b.apiKey(o.get("api_key"));
            return new io.github.llm4j.agent.tool.SkillDiscoveryTool(b.build());
        }));
    }

    @FunctionalInterface
    interface Creator {
        Tool create(String name, Map<String, String> options, Path baseDir) throws Exception;
    }

    private static ToolKind simple(String kind, Set<String> required, Set<String> optional, Set<String> secrets,
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
            public Tool create(String name, Map<String, String> options, Path baseDir) throws Exception {
                return creator.create(name, options, baseDir);
            }
        };
    }

    public ToolFactory register(ToolKind kind) {
        kinds.put(kind.name(), kind);
        return this;
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
     * Everything wrong with a declaration, as messages (no secret values in them). Nothing is created or
     * contacted.
     */
    public List<String> problems(ToolDef def, Function<String, String> env) {
        return problems(def, env, Path.of("").toAbsolutePath());
    }

    /** As {@link #problems(ToolDef, Function)}, resolving files against the script's directory. */
    public List<String> problems(ToolDef def, Function<String, String> env, Path baseDir) {
        List<String> out = new java.util.ArrayList<>();
        ToolKind kind = kinds.get(def.getKind());
        if (kind == null) {
            out.add("unknown tool kind '" + def.getKind() + "'; use one of " + kinds());
            return out;
        }
        for (String req : kind.required()) {
            if (def.getOptions().containsKey(req)) continue;
            ToolDef.OptionValue fallback = kind.defaults().get(req);
            if (fallback == null) {
                out.add("use: " + kind.name() + " needs " + req + ":");
            } else if (fallback.fromEnv() && isUnset(env.apply(fallback.value()))) {
                out.add("environment variable " + fallback.value() + " is not set (for " + req
                        + "; or give " + req + ": env.<NAME>)");
            }
        }
        for (Map.Entry<String, ToolDef.OptionValue> e : def.getOptions().entrySet()) {
            String key = e.getKey();
            if (key.equals(DESCRIPTION)) continue;
            String prefix = prefixOf(kind, key);
            if (prefix != null) {
                String problem = checkPrefixed(prefix, key, e.getValue(), env);
                if (problem != null) out.add(problem);
                continue;
            }
            if (!kind.required().contains(key) && !kind.optional().contains(key)) {
                out.add("unknown option " + key + " for use: " + kind.name()
                        + (kind.required().isEmpty() && kind.optional().isEmpty() ? " (it takes none)"
                        : "; it takes " + new TreeSet<>(union(kind.required(), kind.optional()))
                        + (kind.prefixes().isEmpty() ? "" : " and " + kind.prefixes() + "<name>")));
                continue;
            }
            if (kind.secrets().contains(key) && !e.getValue().fromEnv()) {
                out.add(key + " must come from the environment, e.g. " + key + ": env."
                        + suggestEnv(kind.name(), def.getName(), key));
                continue;
            }
            if (e.getValue().fromEnv()) {
                String v = env.apply(e.getValue().value());
                if (v == null || v.isEmpty()) out.add("environment variable " + e.getValue().value() + " is not set (for " + key + ")");
            }
        }
        if (out.isEmpty()) {
            String extra = kind.check(resolve(kind, def, env), baseDir);
            if (extra != null) out.add(extra);
        }
        return out;
    }

    /** What is wrong with an agent using this declared tool, given whether the agent has it under {@code approve:}. */
    public String agentProblem(ToolDef def, Function<String, String> env, String agentName, boolean approved) {
        ToolKind kind = kinds.get(def.getKind());
        return kind == null ? null : kind.agentProblem(resolve(kind, def, env), def.getName(), agentName, approved);
    }

    private static String prefixOf(ToolKind kind, String key) {
        for (String p : kind.prefixes()) if (key.startsWith(p) && key.length() > p.length()) return p;
        return null;
    }

    /** A {@code header.<Name>} option: a credential-looking header must come from the environment. */
    private static String checkPrefixed(String prefix, String key, ToolDef.OptionValue value, Function<String, String> env) {
        if (prefix.equals(Options.HEADER_PREFIX) && Options.isSecretHeader(key.substring(prefix.length())) && !value.fromEnv()) {
            return key + " must come from the environment, e.g. " + key + ": env."
                    + key.substring(prefix.length()).toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        }
        if (value.fromEnv() && isUnset(env.apply(value.value()))) {
            return "environment variable " + value.value() + " is not set (for " + key + ")";
        }
        return null;
    }

    private static boolean isUnset(String value) {
        return value == null || value.isBlank();
    }

    /** Builds the tool, presented under its declared name. Call only when {@link #problems} is empty. */
    public Tool create(ToolDef def, Function<String, String> env, Path baseDir) throws Exception {
        return create(def, env, baseDir, EffectContext.noop());
    }

    /**
     * As {@link #create(ToolDef, Function, Path)}, for a tool that runs inside a run: side-effect tools are
     * journaled through {@code context} so a resumed run doesn't repeat them.
     */
    public Tool create(ToolDef def, Function<String, String> env, Path baseDir, EffectContext context) throws Exception {
        ToolKind kind = kinds.get(def.getKind());
        Map<String, String> options = resolve(kind, def, env);
        String description = options.remove(DESCRIPTION);
        Tool tool = kind.create(def.getName(), options, baseDir, context);
        if (tool instanceof Effectful effectful) tool = new EffectTool(effectful, context);
        if (description != null) tool = new DescribedTool(tool, description);
        return new NamedTool(def.getName(), tool);
    }

    private static Map<String, String> resolve(ToolKind kind, ToolDef def, Function<String, String> env) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, ToolDef.OptionValue> all = new LinkedHashMap<>(kind.defaults());
        all.putAll(def.getOptions());
        all.forEach((k, v) -> {
            String value = v.fromEnv() ? env.apply(v.value()) : v.value();
            if (!isUnset(value)) out.put(k, value);
        });
        return out;
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
