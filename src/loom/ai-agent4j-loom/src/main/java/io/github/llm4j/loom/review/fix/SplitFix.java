package io.github.llm4j.loom.review.fix;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.ParallelStmt;
import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.review.fact.Entity;
import io.github.llm4j.loom.review.model.FixOption;
import io.github.llm4j.loom.review.model.FixSafety;
import io.github.llm4j.loom.review.model.TextEdit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * LP10's {@code split} option (docs/specs/25-reason-then-extract, R3): an agent that has to think
 * stops answering in a big schema and answers in prose, and a new extractor pulls the old schema's
 * fields out of that prose. Every later use of the answer's variable is unchanged, because the
 * extract step writes it. It is offered only when the rewrite is mechanical and nothing is lost:
 * not for a broadcast, not when the steps disagree about
 * the schema, and not when a statement is not on a line of its own. A later prompt that writes the
 * whole answer ({@code {report}}) keeps working, because the extract step writes the same variable
 * with the same fields. It never edits tests; it lists
 * the test files that script the agent.
 */
public final class SplitFix implements JavaFixOption {

    /** The id of the option, for {@code --fix-finding … --option}. */
    public static final String ID = "split";

    /** Added to the end of the agent's system prompt. Fixed here so it can be reviewed. */
    static final String PROSE_SENTENCE =
            "Answer in plain prose; explain your reasoning, then state your conclusions.";

    /** The instructions of the new extractor. */
    static final String EXTRACTOR_INSTRUCTIONS = "Pull these fields out of the analysis.";

    /** A cheaper model of the same provider, by the start of the model's name. */
    private static final Map<String, String> CHEAPER =
            Map.of("claude", "claude-haiku-5-5", "gemini", "gemini-2.5-flash");

    /** The most lines a delegate's task text may run over for the rewrite to find its arrow. */
    private static final int MAX_PROMPT_LINES = 80;

    private static final Pattern EXPECTING = Pattern.compile("\\bexpecting\\s*\\{");

    @Override
    public String ruleId() {
        return "LP10";
    }

    @Override
    public Offer offer(Entity entity, LoomScript script, SourceText source, String file, Path baseDir) {
        String name = String.valueOf(entity.get("name"));
        AgentDef agent =
                script.getAgents().stream().filter(a -> a.getName().equals(name)).findFirst().orElse(null);
        if (agent == null) return Offer.refused("the agent is not in this file");

        // A script that imports others is one merged script, whose line numbers belong to several
        // files. Only edit when the agent really is declared at its line in this file.
        int declared = agent.getLine();
        if (declared < 1
                || declared > source.lineCount()
                || !Pattern.compile("^\\s*agent\\s+" + Pattern.quote(name) + "\\b")
                        .matcher(SourceText.code(source.line(declared)))
                        .find())
            return Offer.refused("the agent is declared in another file (an import), and a rewrite edits one file at a time: apply it there by hand");
        List<DelegateStmt> delegates = new ArrayList<>();
        boolean[] broadcast = {false};
        for (var w : script.getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof DelegateStmt d && name.equals(d.getTargetAgent())) delegates.add(d);
                        if (s instanceof BroadcastStmt b && b.getTargetAgents().contains(name))
                            broadcast[0] = true;
                    });
        boolean[] chosenAtRunTime = {false};
        for (var w : script.getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof DelegateStmt d && d.hasDynamicTarget()) chosenAtRunTime[0] = true;
                    });
        if (chosenAtRunTime[0])
            return Offer.refused("a step in this script chooses its agent at run time and may reach this agent, so the rewrite cannot tell which steps to change");
        if (broadcast[0]) return Offer.refused("the agent is also used in a broadcast, which has no single answer to extract from");
        if (delegates.isEmpty()) return Offer.refused("nothing delegates to the agent");

        // one schema for every step: the agent's own, or the same expecting on every step
        SchemaDef schema;
        boolean fromAgent = agent.getOutputSchema() != null;
        if (fromAgent) {
            if (delegates.stream().anyMatch(d -> d.getExpecting() != null))
                return Offer.refused("some steps give their own expecting schema and others use the agent's, so the schemas differ");
            schema = agent.getOutputSchema();
        } else {
            if (delegates.stream().anyMatch(d -> d.getExpecting() == null))
                return Offer.refused("the agent has no schema to extract");
            schema = delegates.get(0).getExpecting();
            String first = ToExtractorFix.type(schema);
            if (delegates.stream().anyMatch(d -> !ToExtractorFix.type(d.getExpecting()).equals(first)))
                return Offer.refused("the steps' expecting schemas differ, so there is no one set of fields to extract");
        }
        if (schema.getType() != SchemaDef.Type.OBJECT)
            return Offer.refused("the schema is not an object with fields");

        Set<String> variables = new LinkedHashSet<>();
        for (DelegateStmt d : delegates) {
            String v = d.getVariableName();
            if (v == null || v.contains("{"))
                return Offer.refused("the step on line " + d.getLine() + " stores its answer under a name chosen at run time");
            variables.add(v);
        }
        String extractorName = name + "Fields";
        if (script.findExtractor(extractorName) != null || script.getAgents().stream().anyMatch(a -> a.getName().equals(extractorName)))
            return Offer.refused("a name " + extractorName + " already exists");

        List<TextEdit> edits = new ArrayList<>();
        int start = agent.getLine();
        int end = source.blockEnd(start);
        // the agent: lose output_schema (when it has one), gain the prose sentence
        Optional<List<TextEdit>> agentEdits = agentEdits(source, file, agent, start, end, fromAgent);
        if (agentEdits.isEmpty())
            return Offer.refused("the agent is not written in a form the rewrite can edit (a system prompt over several lines, or a schema it cannot find)");
        edits.addAll(agentEdits.get());

        Set<String> required = requiredFields(script, variables);
        Map<String, SchemaDef> fields = schema.getFields();
        String model = cheaper(agent.getModel());
        String indent = indentOf(source.line(start));
        edits.add(
                new TextEdit(
                        file,
                        end,
                        source.line(end).length() + 1,
                        end,
                        source.line(end).length() + 1,
                        source.eol()
                                + source.eol()
                                + extractor(extractorName, model, fields, required, indent, source.eol())));

        Set<DelegateStmt> inParallel = new LinkedHashSet<>();
        for (var w : script.getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof ParallelStmt p)
                            for (Statement child : p.getBody())
                                if (child instanceof DelegateStmt d && delegates.contains(d)) inParallel.add(d);
                    });
        // extract lines to add, by the line they follow (several may follow the same line)
        java.util.TreeMap<Integer, List<Insertion>> after = new java.util.TreeMap<>();
        for (DelegateStmt d : delegates) {
            Optional<Step> step = step(source, file, d, name, extractorName, !fromAgent, inParallel);
            if (step.isEmpty())
                return Offer.refused("the step on line " + d.getLine() + " is not a delegate on a line of its own that the rewrite can edit");
            edits.addAll(step.get().edits());
            Insertion at = step.get().insertion();
            int line = at.line();
            if (inParallel.contains(d)) {
                // each statement of a parallel block is its own branch, so the extract goes after the block
                OptionalInt close = closingLine(source, at.line());
                if (close.isEmpty()) return Offer.refused("the parallel block around line " + d.getLine() + " could not be read");
                line = close.getAsInt();
                at = new Insertion(line, indentOf(source.line(blockOpener(source, line))), at.text());
            }
            after.computeIfAbsent(line, k -> new ArrayList<>()).add(at);
        }
        for (Map.Entry<Integer, List<Insertion>> e : after.entrySet()) {
            int col = source.line(e.getKey()).length() + 1;
            StringBuilder text = new StringBuilder();
            for (Insertion i : e.getValue()) text.append(source.eol()).append(i.indent()).append(i.text());
            edits.add(new TextEdit(file, e.getKey(), col, e.getKey(), col, text.toString()));
        }
        if (!SourceText.disjoint(edits)) return Offer.refused("the edits would overlap");

        List<String> tests = testsScripting(baseDir, name);
        String explain =
                "Agent "
                        + name
                        + " keeps its prompt, tools and settings, loses its schema and is asked to answer in prose; a new extractor "
                        + extractorName
                        + " ("
                        + model
                        + (model.equals(agent.getModel()) ? ", the agent's own model: change it to a cheaper one" : ", a cheaper model of the same provider")
                        + ") pulls the same fields out of that prose. "
                        + delegates.size()
                        + " step(s) gain an extract step that writes the same variable, so later uses are unchanged. "
                        + (required.isEmpty() ? "" : "Fields a condition reads stay required; the others become optional. ")
                        + "The new extract step has no on_failure: add one if a failure should not stop the run. "
                        + (tests.isEmpty()
                                ? ""
                                : "Test files that script "
                                        + name
                                        + " and need updating (the fix never edits tests): "
                                        + String.join(", ", tests)
                                        + ". ");
        explain = explain + GoldenCasesNote.forNewExtractor(baseDir, extractorName, name);
        return Offer.of(
                new FixOption(ID, "Let " + name + " answer in prose and extract its fields", explain.strip(), FixSafety.REVIEW, List.of(), edits, false));
    }

    // ---- the agent

    private static Optional<List<TextEdit>> agentEdits(
            SourceText source, String file, AgentDef agent, int start, int end, boolean removeSchema) {
        List<TextEdit> edits = new ArrayList<>();
        if (removeSchema) {
            Optional<TextEdit> removal = removeSetting(source, file, "output_schema", start, end);
            if (removal.isEmpty()) return Optional.empty();
            edits.add(removal.get());
        }
        // the sentence: inside the inline system prompt, else a new system: setting
        for (int line = start; line <= end; line++) {
            String code = SourceText.code(source.line(line));
            Matcher m = Pattern.compile("\\bsystem\\s*:\\s*\"").matcher(code);
            if (!m.find()) continue;
            int i = m.end();
            while (i < code.length()) {
                char c = code.charAt(i);
                if (c == '\\') i += 2;
                else if (c == '"') break;
                else i++;
            }
            if (i >= code.length()) return Optional.empty(); // the string goes on over several lines
            edits.add(new TextEdit(file, line, i + 1, line, i + 1, " " + PROSE_SENTENCE));
            return Optional.of(edits);
        }
        String code = SourceText.code(source.line(end));
        int close = code.lastIndexOf('}');
        if (close < 0) return Optional.empty();
        if (start == end) {
            edits.add(new TextEdit(file, end, close + 1, end, close + 1, " system: " + ToExtractorFix.quote(PROSE_SENTENCE) + " "));
        } else {
            String indent = indentOf(source.line(end)) + "    ";
            edits.add(new TextEdit(file, end, 1, end, 1, indent + "system: " + ToExtractorFix.quote(PROSE_SENTENCE) + source.eol()));
        }
        return Optional.of(edits);
    }

    /** The edit that deletes {@code setting: { … }} (balanced braces) inside the block, or empty. */
    private static Optional<TextEdit> removeSetting(
            SourceText source, String file, String setting, int start, int end) {
        for (int line = start; line <= end; line++) {
            String code = SourceText.code(source.line(line));
            Matcher m = Pattern.compile("\\b" + setting + "\\s*:\\s*\\{").matcher(code);
            if (!m.find()) continue;
            int col = m.start();
            int depth = 0;
            boolean quoted = false;
            int l = line;
            int c = m.end() - 1;
            while (l <= end) {
                String text = SourceText.code(source.line(l));
                for (; c < text.length(); c++) {
                    char ch = text.charAt(c);
                    if (ch == '"' && (c == 0 || text.charAt(c - 1) != '\\')) quoted = !quoted;
                    else if (!quoted && ch == '{') depth++;
                    else if (!quoted && ch == '}' && --depth == 0) {
                        return Optional.of(deletion(source, file, line, col, l, c + 1));
                    }
                }
                l++;
                c = 0;
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Deletes the span, with the whole line(s) when nothing else is on them. */
    private static TextEdit deletion(SourceText source, String file, int l1, int c1, int l2, int c2) {
        String first = source.line(l1);
        String last = source.line(l2);
        boolean alone = first.substring(0, c1).isBlank() && SourceText.code(last).substring(c2).isBlank();
        if (alone && l2 < source.lineCount()) return new TextEdit(file, l1, 1, l2 + 1, 1, "");
        // keep the line: also drop the spaces before the span
        int from = c1;
        while (from > 0 && first.charAt(from - 1) == ' ') from--;
        return new TextEdit(file, l1, from + 1, l2, c2 + 1, "");
    }

    // ---- the steps

    /** Where an extract line goes: after {@code line}, indented, with its text. */
    private record Insertion(int line, String indent, String text) {}

    /** The edits for one step, and the extract line it needs (placed later, grouped by position). */
    private record Step(List<TextEdit> edits, Insertion insertion) {}

    private static Optional<Step> step(
            SourceText source,
            String file,
            DelegateStmt d,
            String agent,
            String extractor,
            boolean removeExpecting,
            Set<DelegateStmt> inParallel) {
        int first = d.getLine();
        if (first < 1 || first > source.lineCount()) return Optional.empty();
        if (!SourceText.code(source.line(first)).stripLeading().startsWith("delegate"))
            return Optional.empty();
        // the task text may run over several lines: the arrow is on the line where it ends
        Pattern header =
                Pattern.compile("\\bto\\s+" + Pattern.quote(agent) + "\\b.*?->\\s*([A-Za-z_][A-Za-z0-9_]*)");
        int line = first;
        Matcher m = null;
        String code = null;
        for (; line <= Math.min(source.lineCount(), first + MAX_PROMPT_LINES); line++) {
            code = source.line(line);
            if (line == first) code = SourceText.code(code);
            m = header.matcher(code);
            if (m.find()) break;
            m = null;
        }
        if (m == null) return Optional.empty();
        String variable = m.group(1);
        List<TextEdit> edits = new ArrayList<>();
        edits.add(new TextEdit(file, line, m.start(1) + 1, line, m.end(1) + 1, variable + "_analysis"));
        if (removeExpecting) {
            Matcher e = EXPECTING.matcher(code);
            if (!e.find(m.end())) return Optional.empty();
            int depth = 0;
            int c = e.end() - 1;
            boolean quoted = false;
            int close = -1;
            for (; c < code.length(); c++) {
                char ch = code.charAt(c);
                if (ch == '"' && (c == 0 || code.charAt(c - 1) != '\\')) quoted = !quoted;
                else if (!quoted && ch == '{') depth++;
                else if (!quoted && ch == '}' && --depth == 0) {
                    close = c;
                    break;
                }
            }
            if (close < 0) return Optional.empty();
            int from = e.start();
            while (from > 0 && code.charAt(from - 1) == ' ') from--;
            edits.add(new TextEdit(file, line, from + 1, line, close + 2, ""));
        }

        // the failure handler, on the header line or on the next line, is repeated on the extract:
        // when the step failed and the run went on, the extract has nothing to read
        int handlerLine = -1;
        int handlerCol = -1;
        Matcher onFailure = Pattern.compile("\\bon_failure\\b").matcher(code);
        if (onFailure.find(m.end())) {
            handlerLine = line;
            handlerCol = onFailure.start();
        } else if (line < source.lineCount()) {
            String next = SourceText.code(source.line(line + 1));
            if (next.stripLeading().startsWith("on_failure")) {
                handlerLine = line + 1;
                handlerCol = next.indexOf("on_failure");
            }
        }
        int endLine = handlerLine < 0 ? line : statementEnd(source, handlerLine, handlerCol);
        String handler = "";
        if (handlerLine >= 0) {
            StringBuilder h = new StringBuilder(" ");
            String firstPart = SourceText.code(source.line(handlerLine)).substring(handlerCol).stripTrailing();
            h.append(firstPart);
            for (int l = handlerLine + 1; l <= endLine; l++) h.append(source.eol()).append(source.line(l));
            handler = h.toString();
        }
        String text = "extract " + extractor + " from " + variable + "_analysis -> " + variable + handler;
        Insertion where = new Insertion(endLine, indentOf(source.line(first)), text);
        return Optional.of(new Step(edits, where));
    }

    /** The line of the first {@code }} that closes the block the given line is inside of. */
    private static OptionalInt closingLine(SourceText source, int fromLine) {
        int depth = 0;
        boolean quoted = false;
        for (int l = fromLine; l <= source.lineCount(); l++) {
            String text = SourceText.code(source.line(l));
            // the rest of the statement's own last line is not scanned: it is already balanced
            for (int c = l == fromLine ? text.length() : 0; c < text.length(); c++) {
                char ch = text.charAt(c);
                if (ch == '"' && (c == 0 || text.charAt(c - 1) != '\\')) quoted = !quoted;
                else if (!quoted && ch == '{') depth++;
                else if (!quoted && ch == '}' && --depth < 0) return OptionalInt.of(l);
            }
        }
        return OptionalInt.empty();
    }

    /** The line that opens the block closed on {@code closeLine}: the nearest earlier unmatched {@code {}. */
    private static int blockOpener(SourceText source, int closeLine) {
        int depth = 0;
        for (int l = closeLine; l >= 1; l--) {
            String text = SourceText.code(source.line(l));
            for (int c = text.length() - 1; c >= 0; c--) {
                char ch = text.charAt(c);
                if (ch == '}') depth++;
                else if (ch == '{' && --depth == 0) return l;
            }
        }
        return closeLine;
    }

    /**
     * The last line of the statement whose header ends at {@code fromColumn} of {@code line}: that
     * line when no block follows the header, else the line that closes the block ({@code on_failure
     * { … }}). It starts at the header, not at the line start, because the first part of that line
     * may still be the end of a multi-line task text.
     */
    private static int statementEnd(SourceText source, int line, int fromColumn) {
        int depth = 0;
        boolean opened = false;
        boolean quoted = false;
        for (int l = line; l <= source.lineCount(); l++) {
            String text = SourceText.code(source.line(l));
            for (int c = l == line ? fromColumn : 0; c < text.length(); c++) {
                char ch = text.charAt(c);
                if (ch == '"' && (c == 0 || text.charAt(c - 1) != '\\')) quoted = !quoted;
                else if (!quoted && ch == '{') {
                    depth++;
                    opened = true;
                } else if (!quoted && ch == '}') depth--;
            }
            if (!opened || depth <= 0) return l;
        }
        return line;
    }

    // ---- facts about the script

    /** The fields a condition reads from any of the variables: they stay required. */
    private static Set<String> requiredFields(LoomScript script, Set<String> variables) {
        Set<String> fields = new LinkedHashSet<>();
        for (var w : script.getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        String condition =
                                s instanceof AltStmt a
                                        ? a.getCondition()
                                        : s instanceof LoopStmt l ? l.getCondition() : null;
                        if (condition == null) return;
                        for (String v : variables) {
                            Matcher m = Pattern.compile("\\b" + Pattern.quote(v) + "\\.([A-Za-z_]\\w*)").matcher(condition);
                            while (m.find()) fields.add(m.group(1));
                        }
                    });
        return fields;
    }

    /** The test files in the script's folder or its {@code tests} folder that name the agent. */
    private static List<String> testsScripting(Path baseDir, String agent) {
        if (baseDir == null) return List.of();
        List<String> out = new ArrayList<>();
        Pattern named = Pattern.compile("(?m)^\\s*" + Pattern.quote(agent) + "\\s*:");
        for (Path dir : List.of(baseDir, baseDir.resolve("tests"))) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : files.sorted().toList()) {
                    String n = p.getFileName().toString();
                    if (!(n.endsWith(".test.yaml") || n.endsWith(".test.yml"))) continue;
                    if (named.matcher(Files.readString(p)).find())
                        out.add(baseDir.relativize(p).toString().replace('\\', '/'));
                }
            } catch (IOException | RuntimeException e) {
                // a folder that cannot be read is simply not listed
            }
        }
        return out;
    }

    // ---- the new extractor

    private static String cheaper(String model) {
        if (model == null) return "";
        String lower = model.toLowerCase(java.util.Locale.ROOT);
        String bare = lower.contains("/") ? lower.substring(lower.indexOf('/') + 1) : lower;
        for (Map.Entry<String, String> e : CHEAPER.entrySet())
            if (bare.startsWith(e.getKey())) return e.getValue();
        return model;
    }

    private static String extractor(
            String name, String model, Map<String, SchemaDef> fields, Set<String> required, String indent, String eol) {
        List<String> out = new ArrayList<>();
        out.add("extractor " + name + " {");
        out.add("    model: " + ToExtractorFix.quote(model));
        out.add("    instructions: " + ToExtractorFix.quote(EXTRACTOR_INSTRUCTIONS));
        out.add("    fields {");
        for (Map.Entry<String, SchemaDef> f : fields.entrySet())
            out.add("        " + f.getKey() + ": " + ToExtractorFix.type(f.getValue()));
        out.add("    }");
        List<String> optional = fields.keySet().stream().filter(f -> !required.contains(f)).toList();
        if (!optional.isEmpty()) out.add("    optional: [" + String.join(", ", optional) + "]");
        out.add("}");
        return out.stream().map(l -> indent + l).collect(Collectors.joining(eol));
    }

    private static String indentOf(String line) {
        return line.substring(0, line.length() - line.stripLeading().length());
    }
}
