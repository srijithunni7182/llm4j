package io.github.llm4j.tools.support;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.tools.DescribedTool;
import io.github.llm4j.tools.EffectContext;
import io.github.llm4j.tools.EmailKind;
import io.github.llm4j.tools.FileKind;
import io.github.llm4j.tools.GenericKind;
import io.github.llm4j.tools.HttpKind;
import io.github.llm4j.tools.Options;
import io.github.llm4j.tools.ShellKind;
import io.github.llm4j.tools.SqlKind;
import io.github.llm4j.tools.WebhookKind;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a tool from the text of a script-style declaration, {@code tool Name { use: webhook url: env.HOOK }},
 * so the tests read the way a user's script does. This is a small reader for tests only: the real parser is
 * Loom's, and Loom's own tests cover it. {@code env.NAME} values come from the map given to the constructor.
 */
public final class Declared {

    private static final Pattern PAIR = Pattern.compile(
            "(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([A-Za-z_][\\w.\\-]*))\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|(\\S+))");

    private final Map<String, GenericKind> kinds = new LinkedHashMap<>();
    private final Map<String, String> env;
    private final Path dir;

    public Declared(Map<String, String> env, Path dir, GenericKind... replacing) {
        for (GenericKind k : List.of(new WebhookKind(), new EmailKind(), new HttpKind(), new FileKind(), new ShellKind(), new SqlKind())) {
            kinds.put(k.name(), k);
        }
        for (GenericKind k : replacing) kinds.put(k.name(), k);
        this.env = env;
        this.dir = dir;
    }

    /** What is wrong with the declaration at load time (empty when it's fine). */
    public List<String> problems(String declaration) {
        Parsed p = parse(declaration);
        List<String> out = new ArrayList<>(p.problems);
        if (!out.isEmpty()) return out;
        GenericKind kind = kinds.get(p.kind);
        if (kind == null) return List.of("unknown tool kind '" + p.kind + "'");
        for (String req : kind.required()) if (!p.options.containsKey(req)) out.add("use: " + p.kind + " needs " + req + ":");
        for (String key : p.options.keySet()) {
            boolean known = kind.required().contains(key) || kind.optional().contains(key) || kind.secrets().contains(key)
                    || kind.prefixes().stream().anyMatch(key::startsWith);
            if (!known) out.add("unknown option " + key + " for use: " + p.kind);
        }
        if (!out.isEmpty()) return out;
        String problem = kind.check(p.options, dir);
        if (problem != null) out.add(problem);
        return out;
    }

    /** The tool, as a host would build it; fails the test if the declaration has problems. */
    public Tool create(String declaration, EffectContext context) throws Exception {
        List<String> problems = problems(declaration);
        if (!problems.isEmpty()) throw new AssertionError("declaration has problems: " + problems);
        Parsed p = parse(declaration);
        Tool tool = kinds.get(p.kind).create(p.name, p.options, dir, context);
        return p.description == null ? tool : new DescribedTool(tool, p.description);
    }

    private record Parsed(String name, String kind, Map<String, String> options, List<String> problems, String description) { }

    private Parsed parse(String declaration) {
        Matcher head = Pattern.compile("tool\\s+(\\w+)\\s*\\{(.*)}\\s*", Pattern.DOTALL).matcher(declaration.strip());
        if (!head.matches()) throw new AssertionError("not a tool declaration: " + declaration);
        Map<String, String> options = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        String kind = null;
        String description = null;
        Matcher m = PAIR.matcher(head.group(2));
        while (m.find()) {
            String key = m.group(1) != null ? unescape(m.group(1)) : m.group(2);
            String value = m.group(3) != null ? unescape(m.group(3)) : m.group(4);
            boolean fromEnv = m.group(4) != null && value.startsWith("env.");
            GenericKind k = kinds.get(kind);
            if (k != null && k.secrets().contains(key) && !fromEnv) {
                problems.add(key + " must come from the environment, e.g. " + key + ": env.NAME");
                continue;
            }
            if (key.startsWith("header.") && Options.isSecretHeader(key.substring("header.".length())) && !fromEnv) {
                problems.add(key + " must come from the environment, e.g. " + key + ": env.NAME");
                continue;
            }
            if (fromEnv) {
                String name = value.substring(4);
                String resolved = env.get(name);
                if (resolved == null) { problems.add(key + ": environment variable " + name + " is not set"); continue; }
                value = resolved;
            }
            if (key.equals("use")) kind = value;
            else if (key.equals("description")) description = value;
            else options.put(key, value);
        }
        return new Parsed(head.group(1), kind, options, problems, description);
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
    }
}
