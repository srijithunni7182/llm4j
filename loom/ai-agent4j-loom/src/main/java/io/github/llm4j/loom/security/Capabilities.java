package io.github.llm4j.loom.security;

import io.github.llm4j.loom.ast.ToolDef;
import java.util.Locale;
import java.util.Set;

/**
 * What a tool lets an agent do, in the three terms that make up the "lethal trifecta": read content it can't trust,
 * reach private data, and send or change something beyond the run. Read from the tool's kind and options; a tool the
 * audit can't see into (a Java class, an MCP server, one registered by the host) is assumed to do all three.
 *
 * @param untrusted reads content from outside (the web, an API, a third-party server)
 * @param privateData reaches data that is yours (files, a database, indexed documents)
 * @param outward sends or changes something beyond the run (a message, a request that writes, a program)
 * @param effect   the call changes something, so it should be approved (outward, or a local write)
 * @param known    the audit knows this kind of tool
 */
public record Capabilities(boolean untrusted, boolean privateData, boolean outward, boolean effect, boolean known, String note) {

    static final Set<String> SEARCH = Set.of("duckduckgo", "serpapi", "google_search", "web_search");
    static final Set<String> HARMLESS = Set.of("calculator", "datetime", "current_time", "translate", "transliterate", "detect_language", "speak", "transcribe");

    static Capabilities none(String note) {
        return new Capabilities(false, false, false, false, true, note);
    }

    static Capabilities unknown(String note) {
        return new Capabilities(true, true, true, true, false, note);
    }

    /** A built-in tool used by name, or one the host registered (null declaration). */
    static Capabilities ofName(String name) {
        if (SEARCH.contains(name)) return new Capabilities(true, false, false, false, true, "web search results");
        if (HARMLESS.contains(name)) return none("built-in");
        return unknown("registered by the host (.loot or Java): the audit can't see what it does");
    }

    static Capabilities of(ToolDef t) {
        String kind = t.getKind() == null ? "" : t.getKind().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "duckduckgo", "serpapi", "google_search" -> new Capabilities(true, false, false, false, true, "web search results");
            case "calculator", "datetime", "current_time", "translate", "transliterate", "detect_language", "speak", "transcribe" -> none(kind);
            case "webhook" -> new Capabilities(false, false, true, true, true, "posts to a fixed URL");
            case "email" -> new Capabilities(false, false, true, true, true, opt(t, "to") != null ? "mails fixed recipients" : "mails recipients the agent chooses from allow_to");
            case "http" -> {
                boolean writes = writes(opt(t, "methods"));
                yield new Capabilities(true, opt(t, "auth_value") != null || opt(t, "auth_header") != null, writes, writes, true,
                        writes ? "calls " + opt(t, "base_url") + " with " + opt(t, "methods") : "reads from " + opt(t, "base_url"));
            }
            case "file" -> {
                String mode = opt(t, "mode") == null ? "read" : opt(t, "mode");
                boolean reads = mode.contains("read");
                boolean writes = !mode.equals("read");
                yield new Capabilities(false, reads, false, writes, true, mode + " in " + (opt(t, "root") == null ? "." : opt(t, "root")));
            }
            case "shell" -> new Capabilities(false, true, true, true, true, "runs " + opt(t, "allow"));
            case "sql" -> new Capabilities(false, true, false, false, true, "read-only queries");
            case "openapi" -> new Capabilities(true, true, true, true, true, "any operation in " + opt(t, "spec"));
            case "knowledge_graph" -> new Capabilities(false, true, false, !"true".equals(opt(t, "read_only")), true, "a knowledge graph");
            case "skill_registry" -> new Capabilities(true, false, false, false, true, "skills fetched from " + opt(t, "url"));
            case "class" -> unknown("Java class " + opt(t, "class") + ": the audit can't see what it does");
            default -> unknown("tool kind \"" + kind + "\" is not one the audit knows");
        };
    }

    static boolean writes(String methods) {
        if (methods == null) return false;
        for (String m : methods.split(",")) if (!m.strip().equalsIgnoreCase("GET") && !m.isBlank()) return true;
        return false;
    }

    static String opt(ToolDef t, String name) {
        ToolDef.OptionValue v = t.getOptions().get(name);
        return v == null ? null : (v.isReference() ? v.toString() : v.value());
    }
}
