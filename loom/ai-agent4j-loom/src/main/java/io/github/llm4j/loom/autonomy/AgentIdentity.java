package io.github.llm4j.loom.autonomy;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.KnowledgeDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.PersonaDef;
import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.ast.Settings;
import io.github.llm4j.loom.ast.ToolDef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Which agent a decision is made by: a hash over everything that decides what the agent says, taken from the parsed script and not from its
 * text, so a comment or a changed layout is not a new agent. Evidence earned by one identity is never credited to another.
 *
 * <p>Secrets stay out: a tool option read from the environment contributes its name and not its value, and an option whose key says it is a
 * secret contributes nothing but its presence.
 */
public final class AgentIdentity {

    private static final Pattern SECRETISH = Pattern.compile("(?i).*(key|token|secret|password|passwd|credential|auth).*");
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    private AgentIdentity() { }

    /** The identity of the agent behind {@code decision}, as the script stands; {@code baseDir} resolves the files it reads. */
    public static String of(LoomScript script, DecisionDef decision, Path baseDir) {
        AgentDef agent = script.getAgents().stream().filter(a -> a.getName().equals(decision.getAgent())).findFirst().orElse(null);
        StringBuilder b = new StringBuilder();
        if (agent == null) return sha("no agent " + decision.getAgent());
        line(b, "model", agent.getModel());
        line(b, "system", agent.getSystemPrompt());
        line(b, "template", agent.getSystemTemplate());
        line(b, "temperature", agent.getTemperature());
        line(b, "max_iterations", agent.getMaxIterations());
        line(b, "routing", agent.getRoutingPolicy());
        if (agent.getPersona() != null) {
            line(b, "persona", agent.getPersona());
            for (PersonaDef p : script.getPersonas()) {
                if (p.getName().equals(agent.getPersona())) {
                    line(b, "persona.role", p.getRole());
                    line(b, "persona.expertise", p.getExpertise());
                    line(b, "persona.tone", p.getTone());
                    line(b, "persona.description", p.getDescription());
                    line(b, "persona.constraints", p.getConstraints());
                }
            }
        }
        schema(b, "schema", agent.getOutputSchema());
        if (agent.getGuard() != null) settings(b, "guard", agent.getGuard());
        if (agent.getMemory() != null) settings(b, "memory", agent.getMemory());
        line(b, "approve", agent.isApproveAll() ? "all" : agent.getApprove());
        for (String skill : agent.getSkills()) line(b, "skill", skill + "#" + contentOf(baseDir, skill));
        for (String name : agent.getKnowledgeBases()) {
            for (KnowledgeDef k : script.getKnowledgeBases()) {
                if (k.getName().equals(name)) line(b, "knowledge", k.getName() + "|" + k.getType() + "|" + k.getPath() + "#" + contentOf(baseDir, k.getPath()) + "|" + k.getMode() + "|" + k.getChunkSize() + "|" + k.getTopK());
            }
        }
        for (String name : agent.getMcpServers()) line(b, "mcp", name);
        for (String name : agent.getTools()) {
            ToolDef tool = script.getTools().stream().filter(t -> t.getName().equals(name)).findFirst().orElse(null);
            if (tool == null) {
                line(b, "tool", name + "|registered");
                continue;
            }
            StringBuilder options = new StringBuilder();
            new TreeMap<>(tool.getOptions()).forEach((k, v) -> options.append(k).append('=').append(optionValue(k, v)).append(';'));
            line(b, "tool", name + "|" + tool.getKind() + "|" + options);
        }
        line(b, "decision.choices", decision.getChoices());
        line(b, "decision.group", decision.getGroupBy());
        line(b, "decision.remember", decision.getRemember());
        line(b, "decision.task", decision.getTask());
        decision.getDangerous().forEach(m -> line(b, "decision.dangerous", m.proposed() + ">" + m.decided()));
        return sha(b.toString());
    }

    private static String optionValue(String key, ToolDef.OptionValue v) {
        if (v.isReference()) return (v.fromSecret() ? "secret:" : "env:") + v.value();
        return SECRETISH.matcher(key).matches() ? "(secret)" : v.value();
    }

    private static void settings(StringBuilder b, String name, Settings s) {
        new TreeMap<>(s.getValues()).forEach((k, v) -> line(b, name + "." + k, optionValue(k, v)));
    }

    private static void schema(StringBuilder b, String name, SchemaDef s) {
        if (s == null) return;
        b.append(name).append('=').append(s.getType());
        if (s.getEnumValues() != null) b.append(s.getEnumValues());
        b.append('\n');
        if (s.getFields() != null) s.getFields().forEach((k, v) -> schema(b, name + "." + k, v));
        if (s.getElementType() != null) schema(b, name + "[]", s.getElementType());
    }

    private static void line(StringBuilder b, String key, Object value) {
        if (value != null) b.append(key).append('=').append(value).append('\n');
    }

    /** A hash of what a file, or the files under a directory, hold; the path itself when it is not a local file (a URL, a classpath resource). */
    private static String contentOf(Path baseDir, String reference) {
        if (reference == null) return "";
        String path = reference.startsWith("fs://") ? reference.substring(5) : reference;
        if (path.contains("://")) return reference;
        Path p = baseDir.resolve(path).normalize();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (Files.isRegularFile(p)) {
                feed(digest, p);
            } else if (Files.isDirectory(p)) {
                try (Stream<Path> walk = Files.walk(p)) {
                    for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                        digest.update(p.relativize(f).toString().getBytes(StandardCharsets.UTF_8));
                        feed(digest, f);
                    }
                }
            } else {
                return "missing";
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return "unreadable";
        }
    }

    private static void feed(MessageDigest digest, Path file) throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) {
            digest.update((file.getFileName() + ":" + Files.size(file)).getBytes(StandardCharsets.UTF_8));
            return;
        }
        digest.update(Files.readAllBytes(file));
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A short form for people. */
    public static String shorten(String identity) {
        return identity == null ? "" : identity.substring(0, Math.min(10, identity.length()));
    }
}
