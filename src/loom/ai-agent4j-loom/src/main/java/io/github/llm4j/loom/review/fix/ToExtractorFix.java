package io.github.llm4j.loom.review.fix;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.review.fact.Entity;
import io.github.llm4j.loom.review.model.FixOption;
import io.github.llm4j.loom.review.model.FixSafety;
import io.github.llm4j.loom.review.model.TextEdit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LP09's {@code to-extractor} option (docs/specs/24-stateless-nodes, R10): rewrites an agent that
 * pulls fields out of text into an {@code extractor}, and every {@code delegate} to it into an
 * {@code extract} that names each interpolated variable as an input. It is offered only when it can
 * be done mechanically and nothing the agent says or does is lost: an agent with tools, memory,
 * a budget and the like, a system prompt that writes a variable, or a task with words around the
 * variables that carry meaning is refused, and the finding says why.
 */
public final class ToExtractorFix implements JavaFixOption {

    /** The id of the option, for {@code --fix-finding … --option}. */
    public static final String ID = "to-extractor";

    private static final Pattern VARIABLE_LINE =
            Pattern.compile("^(?:([A-Za-z][A-Za-z0-9 _-]*?)\\s*:\\s*)?\\{([A-Za-z_][A-Za-z0-9_.]*)}\\s*$");

    @Override
    public String ruleId() {
        return "LP09";
    }

    @Override
    public Offer offer(
            Entity entity,
            LoomScript script,
            SourceText source,
            String file,
            java.nio.file.Path baseDir) {
        String name = String.valueOf(entity.get("name"));
        AgentDef agent =
                script.getAgents().stream()
                        .filter(a -> a.getName().equals(name))
                        .findFirst()
                        .orElse(null);
        if (agent == null) return Offer.refused("the agent is not in this file");
        int declared = agent.getLine();
        if (declared < 1
                || declared > source.lineCount()
                || !Pattern.compile("^\\s*agent\\s+" + Pattern.quote(name) + "\\b")
                        .matcher(SourceText.code(source.line(declared)))
                        .find())
            return Offer.refused("the agent is declared in another file (an import), and a rewrite edits one file at a time: apply it there by hand");
        String lost = lostSetting(agent);
        if (lost != null)
            return Offer.refused(
                    "agent " + name + " has " + lost + ", which an extractor cannot carry; remove it first");
        if (agent.getOutputSchema() == null
                || agent.getOutputSchema().getType() != SchemaDef.Type.OBJECT)
            return Offer.refused("agent " + name + " has no object output_schema to become the fields");
        String system = agent.getSystemPrompt();
        if (system != null && system.contains("{"))
            return Offer.refused(
                    "its system prompt writes {…}, and an extractor's instructions are fixed text; pass the value as an input instead");

        List<DelegateStmt> delegates = new ArrayList<>();
        for (var w : script.getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof DelegateStmt d && name.equals(d.getTargetAgent()))
                            delegates.add(d);
                    });
        if (delegates.isEmpty()) return Offer.refused("nothing delegates to the agent");

        List<TextEdit> edits = new ArrayList<>();
        for (DelegateStmt d : delegates) {
            if (d.getExpecting() != null || d.hasOnStopped() || d.isLenientSchema())
                return Offer.refused(
                        "the delegate on line "
                                + d.getLine()
                                + " has expecting, on_stopped or a lenient schema, which extract does not take");
            Optional<String> inputs = inputs(d.getPayload());
            if (inputs.isEmpty())
                return Offer.refused(
                        "the task on line "
                                + d.getLine()
                                + " has text around the variables that carries meaning, so it cannot become named inputs mechanically");
            Optional<TextEdit> edit = head(source, file, d, name, inputs.get());
            if (edit.isEmpty())
                return Offer.refused(
                        "the delegate on line "
                                + d.getLine()
                                + " is not written as delegate \"…\" to "
                                + name
                                + " on one line");
            edits.add(edit.get());
        }
        int start = agent.getLine();
        int end = source.blockEnd(start);
        String indent = source.line(start).substring(0, source.line(start).length() - source.line(start).stripLeading().length());
        edits.add(
                0,
                new TextEdit(
                        file, start, 1, end, source.line(end).length() + 1, extractor(agent, indent, source.eol())));
        if (!SourceText.disjoint(edits)) return Offer.refused("the edits would overlap");
        return Offer.of(
                new FixOption(
                        ID,
                        "Turn " + name + " into an extractor",
                        "Agent "
                                + name
                                + " becomes an extractor: it no longer sees the run's transcript or the shared prompt, and its answer is no longer added to the transcript. "
                                + delegates.size()
                                + " delegate step(s) become extract steps; the lead-in text before the variable is dropped, because the instructions say the job. Test it on its own afterwards.",
                        FixSafety.REVIEW,
                        List.of(),
                        edits,
                        false));
    }

    /** What the agent has that an extractor cannot carry, or null. */
    private static String lostSetting(AgentDef a) {
        if (!a.getTools().isEmpty()) return "tools";
        if (!a.getMcpServers().isEmpty()) return "mcp_servers";
        if (!a.getSkills().isEmpty()) return "skills";
        if (a.getPersona() != null) return "a persona";
        if (a.getMemory() != null) return "memory";
        if (a.getContext() != null) return "a context block";
        if (a.getGrounding() != null) return "grounding";
        if (!a.getKnowledgeBases().isEmpty()) return "knowledge";
        if (a.getBudget() != null) return "a budget";
        if (a.getMaxIterations() != null) return "max_iterations";
        if (!a.getApprove().isEmpty() || a.isApproveAll()) return "approve";
        if (a.isSteerable() || a.isStreaming() || a.isReasoning()) return "steerable, streaming or reasoning";
        if (a.getLadder() != null) return "a ladder";
        if (a.getRoutingPolicy() != null) return "a routing policy";
        if (a.getPromptRef() != null || a.getSystemTemplate() != null) return "a prompt file";
        if (a.getGuard() != null && a.getGuard().getBias() != null) return "a bias guard";
        if (a.isSharedPromptExcluded()) return "shared_prompt: none";
        return null;
    }

    /**
     * The inputs of the extract statement for a task: one variable (a lead-in label ending in a
     * colon is dropped), or one labelled variable per line. Empty when anything else is in it.
     */
    static Optional<String> inputs(String payload) {
        if (payload == null) return Optional.empty();
        // the parser keeps the escapes of a quoted text as written, so a line break is \n here
        List<String> lines =
                payload.replace("\\n", "\n").lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (lines.isEmpty()) return Optional.empty();
        if (lines.size() == 1) {
            Matcher m = VARIABLE_LINE.matcher(lines.get(0));
            return m.matches() ? Optional.of(m.group(2)) : Optional.empty();
        }
        Map<String, String> named = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher m = VARIABLE_LINE.matcher(line);
            if (!m.matches() || m.group(1) == null) return Optional.empty();
            String label = m.group(1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
            if (label.isEmpty() || Character.isDigit(label.charAt(0)) || named.containsKey(label))
                return Optional.empty();
            named.put(label, m.group(2));
        }
        return Optional.of(
                named.entrySet().stream()
                        .map(e -> e.getKey() + " = " + e.getValue())
                        .collect(Collectors.joining(", ", "(", ")")));
    }

    private static Optional<TextEdit> head(
            SourceText source, String file, DelegateStmt d, String agent, String inputs) {
        int line = d.getLine();
        if (line < 1 || line > source.lineCount()) return Optional.empty();
        String code = SourceText.code(source.line(line));
        Matcher m =
                Pattern.compile("\\bdelegate\\s+\"(?:[^\"\\\\]|\\\\.)*\"\\s+to\\s+" + Pattern.quote(agent) + "\\b")
                        .matcher(code);
        if (!m.find()) return Optional.empty();
        return Optional.of(
                new TextEdit(
                        file,
                        line,
                        m.start() + 1,
                        line,
                        m.end() + 1,
                        "extract " + agent + " from " + inputs));
    }

    private static String extractor(AgentDef a, String indent, String eol) {
        List<String> out = new ArrayList<>();
        out.add("extractor " + a.getName() + " {");
        out.add("    model: " + quote(a.getModel()));
        String system = a.getSystemPrompt();
        if (system != null && !system.isBlank()) out.add("    instructions: " + quote(system));
        if (a.getTemperature() != null && a.getTemperature() != 0.0)
            out.add("    temperature: " + a.getTemperature());
        out.add("    fields {");
        for (Map.Entry<String, SchemaDef> f : a.getOutputSchema().getFields().entrySet())
            out.add("        " + f.getKey() + ": " + type(f.getValue()));
        out.add("    }");
        if (a.getGuard() != null && a.getGuard().getPii() != null)
            out.add("    pii: " + a.getGuard().getPii());
        out.add("}");
        return out.stream().map(l -> indent + l).collect(Collectors.joining(eol));
    }

    static String type(SchemaDef s) {
        return switch (s.getType()) {
            case STRING -> "string";
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
            case ENUM -> s.getEnumValues().stream().map(ToExtractorFix::quote).collect(Collectors.joining(", ", "enum[", "]"));
            case LIST -> s.getElementType() == null ? "list" : "list<" + type(s.getElementType()) + ">";
            case OBJECT -> s.getFields().entrySet().stream()
                    .map(e -> e.getKey() + ": " + type(e.getValue()))
                    .collect(Collectors.joining(", ", "{ ", " }"));
        };
    }

    static String quote(String text) {
        return "\""
                + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n")
                + "\"";
    }
}
