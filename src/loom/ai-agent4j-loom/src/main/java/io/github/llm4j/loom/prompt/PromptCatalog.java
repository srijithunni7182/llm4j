package io.github.llm4j.loom.prompt;

import io.github.llm4j.agent.prompt.MarkdownFolderPromptRegistry;
import io.github.llm4j.agent.prompt.PromptRegistry;
import io.github.llm4j.agent.prompt.PromptTemplate;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The prompts a run can use: a registry, plus versions pinned from the command line ({@code --prompt researcher@v2}). A pin
 * on the command line wins over a version written in the script, which wins over "latest".
 */
public final class PromptCatalog {

    /** A prompt chosen for an agent: what it says and where it came from. */
    public record Resolved(String id, String version, String text, Path file) {}

    private final PromptRegistry registry;
    private final MarkdownFolderPromptRegistry folder;
    private final Map<String, String> pins;

    /** A catalog over any registry (for example the YAML one a Java host supplies). */
    public PromptCatalog(PromptRegistry registry, Map<String, String> pins) {
        this.registry = registry;
        this.folder = registry instanceof MarkdownFolderPromptRegistry f ? f : null;
        this.pins = new TreeMap<>(pins == null ? Map.of() : pins);
    }

    /** Pins from {@code id@vN} strings; throws {@link IllegalArgumentException} for anything else, or for two pins on one id. */
    public static Map<String, String> pins(Collection<String> specs) {
        Map<String, String> out = new TreeMap<>();
        for (String spec : specs) {
            PromptRef ref = PromptRef.parse(spec);
            if (!ref.pinned()) {
                throw new IllegalArgumentException("--prompt needs a version: " + ref.id() + "@v1, not just \"" + spec + "\"");
            }
            String previous = out.put(ref.id(), ref.version());
            if (previous != null && !previous.equals(ref.version())) {
                throw new IllegalArgumentException("--prompt pins " + ref.id() + " twice (" + previous + " and " + ref.version() + ")");
            }
        }
        return out;
    }

    public Map<String, String> pins() {
        return pins;
    }

    /** The folder registry behind this catalog, if it is one. */
    public Optional<MarkdownFolderPromptRegistry> folder() {
        return Optional.ofNullable(folder);
    }

    /** The version a reference runs: the command-line pin, else the version in the reference, else null for "latest". */
    public String versionFor(PromptRef ref) {
        String pinned = pins.get(ref.id());
        return pinned != null ? pinned : ref.version();
    }

    /** The prompt for a reference as written in a script, or empty when there is none. */
    public Optional<Resolved> resolve(String reference) {
        PromptRef ref;
        try {
            ref = PromptRef.parse(reference);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String version = versionFor(ref);
        if (folder != null) {
            var entry = version == null ? folder.latest(ref.id()) : folder.entry(ref.id(), version);
            return entry.map(e -> new Resolved(e.id(), e.version(), e.text(), e.file()));
        }
        Optional<PromptTemplate> t = version == null ? registry.get(ref.id()) : registry.get(ref.id(), version);
        return t.map(x -> new Resolved(x.getId(), x.getVersion(), x.getTemplate(), null));
    }

    /**
     * Why a reference cannot be used, in words for the person writing the script, or null when it can. Names the nearest ids or the
     * versions that exist.
     */
    public String problemWith(String reference) {
        return problemWith(reference, "prompt");
    }

    /** As {@link #problemWith(String)}, naming the attribute the script used ({@code prompt} or {@code system_template}). */
    public String problemWith(String reference, String attribute) {
        PromptRef ref;
        try {
            ref = PromptRef.parse(reference);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        String version = versionFor(ref);
        String where = pins.containsKey(ref.id()) ? " (pinned on the command line)" : "";
        if (resolve(reference).isPresent()) return null;
        if (folder != null && !folder.exists()) {
            return "the prompt folder " + folder.root() + " does not exist";
        }
        if (folder == null) {
            return attribute + " " + ref + " is not in the prompt registry" + where;
        }
        if (folder.versions(ref.id()).isEmpty()) {
            List<String> near = nearest(ref.id(), folder.ids());
            return attribute + " " + ref.id() + " has no file in " + folder.root()
                    + (near.isEmpty() ? "" : "; did you mean " + String.join(", ", near) + "?")
                    + " Create " + folder.root().resolve(ref.id() + ".md") + " or " + folder.root().resolve(ref.id()).resolve("v1.md");
        }
        return attribute + " " + ref.id() + " has no version " + version + where + "; it has " + String.join(", ", folder.versions(ref.id()));
    }

    /** The prompt each agent of {@code script} that names one would run, in script order. */
    public List<PromptUse> usesOf(io.github.llm4j.loom.ast.LoomScript script) {
        List<PromptUse> out = new ArrayList<>();
        for (var agent : script.getAgents()) {
            String ref = agent.getPromptRef();
            if (ref == null) continue;
            var r = resolve(ref);
            out.add(r.map(x -> new PromptUse(agent.getName(), agent.getLine(), ref, x.id(), x.version(), hash(x.text()), x.file()))
                    .orElseGet(() -> new PromptUse(agent.getName(), agent.getLine(), ref, null, null, null, null)));
        }
        return out;
    }

    private static String hash(String text) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Up to three of {@code candidates} close to {@code wanted}, nearest first. */
    public static List<String> nearest(String wanted, Collection<String> candidates) {
        List<String> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt((String c) -> distance(wanted, c)).thenComparing(c -> c));
        List<String> out = new ArrayList<>();
        for (String c : sorted) {
            if (out.size() < 3 && distance(wanted, c) <= Math.max(2, wanted.length() / 3)) out.add(c);
        }
        return out;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1, prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            prev = cur;
        }
        return prev[b.length()];
    }
}
