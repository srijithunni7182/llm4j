package io.github.llm4j.loom.symbols;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.execution.Buffers;
import io.github.llm4j.loom.execution.LibraryImports;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the {@link SymbolIndex} of a script and the scripts it imports. Positions come from the
 * language's lexer and the phrases of its parser ({@link FileIndexer}); what each variable holds
 * comes from the parsed definitions ({@link TolerantParse}), so the index agrees with what {@code
 * weave check} understands. Nothing is run and nothing is written; text is read through {@link
 * Buffers}, so an editor's unsaved text is what is indexed.
 */
public final class SymbolService {

    private record Loaded(Path file, FileIndexer index, TolerantParse parse) {}

    public SymbolIndex index(Path entry) {
        Path start = entry.toAbsolutePath().normalize();
        List<SymbolIndex.Problem> problems = new ArrayList<>();
        Map<Path, Loaded> loaded = new LinkedHashMap<>();
        Deque<Path> queue = new ArrayDeque<>(List.of(start));
        Set<Path> seen = new HashSet<>(Set.of(start));
        while (!queue.isEmpty()) {
            Path file = queue.poll();
            String text;
            try {
                text = Buffers.read(file);
            } catch (IOException e) {
                problems.add(new SymbolIndex.Problem(file.toString(), 0, "cannot read " + file));
                continue;
            }
            TolerantParse parse = TolerantParse.of(file.toString(), text);
            problems.addAll(parse.problems());
            FileIndexer index = new FileIndexer(parse.tokens());
            loaded.put(file, new Loaded(file, index, parse));
            for (String imported : index.imports) {
                if (LibraryImports.isLibrary(imported)) continue;
                Path target = file.getParent().resolve(imported).normalize();
                if (Buffers.exists(target) && seen.add(target)) queue.add(target);
            }
        }
        return merge(start, loaded, problems);
    }

    private SymbolIndex merge(
            Path entry, Map<Path, Loaded> loaded, List<SymbolIndex.Problem> problems) {
        // what the scripts define, by name
        Map<String, AgentDef> agents = new HashMap<>();
        Map<String, IntentClassifierDef> classifiers = new HashMap<>();
        Map<String, DecisionDef> decisions = new HashMap<>();
        for (Loaded l : loaded.values()) {
            LoomScript s = l.parse().script();
            s.getAgents().forEach(a -> agents.putIfAbsent(a.getName(), a));
            s.getIntentClassifiers().forEach(c -> classifiers.putIfAbsent(c.getName(), c));
            s.getDecisions().forEach(d -> decisions.putIfAbsent(d.getName(), d));
        }

        List<SymbolIndex.Definition> definitions = new ArrayList<>();
        Set<String> defined = new LinkedHashSet<>();
        for (Loaded l : loaded.values()) {
            String file = l.file().toString();
            for (FileIndexer.RawDef d : l.index().definitions) {
                String key = key(d.kind(), d.scope(), d.name());
                definitions.add(
                        new SymbolIndex.Definition(
                                key,
                                d.kind(),
                                d.name(),
                                file,
                                d.range(),
                                d.scope(),
                                detail(d, agents)));
                defined.add(key);
            }
        }

        List<SymbolIndex.Reference> references = new ArrayList<>();
        for (Loaded l : loaded.values()) {
            String file = l.file().toString();
            for (FileIndexer.RawRef r : l.index().references) {
                String to = refKey(r);
                if (r.kind().equals("file")) {
                    Path target = l.file().getParent().resolve(r.name()).normalize();
                    boolean exists = Buffers.exists(target) || LibraryImports.isLibrary(r.name());
                    references.add(
                            new SymbolIndex.Reference(
                                    "file:" + target, "file", r.role(), file, r.range(), exists));
                } else if (r.kind().equals("prompt")) {
                    Path prompt = promptFile(l, r.name());
                    if (prompt != null && defined.add("prompt:" + r.name()))
                        definitions.add(
                                new SymbolIndex.Definition(
                                        "prompt:" + r.name(),
                                        "prompt",
                                        r.name(),
                                        prompt.toString(),
                                        new Range(0, 0, 0, 0),
                                        null,
                                        null));
                    references.add(
                            new SymbolIndex.Reference(
                                    to, "prompt", r.role(), file, r.range(), prompt != null));
                } else if (r.kind().equals("binding")) {
                    references.add(
                            new SymbolIndex.Reference(
                                    to, "binding", r.role(), file, r.range(), true));
                } else {
                    boolean resolved = defined.contains(to);
                    if (r.weak() && !resolved) continue; // a library persona, a model with a slash
                    references.add(
                            new SymbolIndex.Reference(
                                    to, r.kind(), r.role(), file, r.range(), resolved));
                }
            }
        }

        List<SymbolIndex.Binding> bindings = new ArrayList<>();
        List<SymbolIndex.Scope> scopes = new ArrayList<>();
        for (Loaded l : loaded.values()) {
            String file = l.file().toString();
            for (FileIndexer.RawScope s : l.index().scopes)
                scopes.add(new SymbolIndex.Scope(s.workflow(), file, s.params(), s.range()));
            for (FileIndexer.RawBinding b : l.index().bindings) {
                SymType type = typeOf(b, l, agents, classifiers, decisions);
                bindings.add(
                        new SymbolIndex.Binding(
                                "binding:" + b.workflow() + "/" + b.name(),
                                b.name(),
                                file,
                                b.range(),
                                "workflow:" + b.workflow(),
                                b.role(),
                                b.producedBy(),
                                b.producedAt(),
                                type));
            }
        }

        Comparator<Range> byPlace =
                Comparator.comparingInt(Range::line).thenComparingInt(Range::col);
        definitions.sort(
                Comparator.comparing(SymbolIndex.Definition::file)
                        .thenComparing(SymbolIndex.Definition::range, byPlace));
        references.sort(
                Comparator.comparing(SymbolIndex.Reference::file)
                        .thenComparing(SymbolIndex.Reference::range, byPlace));
        bindings.sort(
                Comparator.comparing(SymbolIndex.Binding::file)
                        .thenComparing(SymbolIndex.Binding::range, byPlace));
        return new SymbolIndex(
                entry.toString(), definitions, references, bindings, scopes, problems);
    }

    private static String key(String kind, String scope, String name) {
        return kind.equals("checkpoint") ? "checkpoint:" + scope + "/" + name : kind + ":" + name;
    }

    private static String refKey(FileIndexer.RawRef r) {
        return switch (r.kind()) {
            case "checkpoint" -> "checkpoint:" + r.scope() + "/" + r.name();
            case "binding" -> "binding:" + r.scope() + "/" + r.name();
            default -> r.kind() + ":" + r.name();
        };
    }

    private static String detail(FileIndexer.RawDef d, Map<String, AgentDef> agents) {
        if (d.kind().equals("agent")) {
            AgentDef a = agents.get(d.name());
            return a == null ? null : a.getModel();
        }
        return null;
    }

    // ---- prompts

    /**
     * The file a {@code prompt: "id"} names, if it is there: {@code dir/id.md} or {@code
     * dir/name/vN.md}.
     */
    private static Path promptFile(Loaded l, String id) {
        String dir = l.index().promptsDir == null ? "prompts" : l.index().promptsDir;
        Path root = l.file().getParent().resolve(dir).normalize();
        String name = id;
        String version = null;
        int at = id.indexOf('@');
        if (at >= 0) {
            name = id.substring(0, at);
            version = id.substring(at + 1);
        }
        if (name.isEmpty() || name.contains("..") || (version != null && version.contains("..")))
            return null;
        List<Path> candidates = new ArrayList<>();
        if (version == null) {
            candidates.add(root.resolve(name + ".md"));
        } else {
            candidates.add(root.resolve(name).resolve(version + ".md"));
            candidates.add(root.resolve(name + "@" + version + ".md"));
        }
        for (Path c : candidates) if (Buffers.exists(c)) return c;
        return null;
    }

    // ---- types

    private static SymType typeOf(
            FileIndexer.RawBinding b,
            Loaded l,
            Map<String, AgentDef> agents,
            Map<String, IntentClassifierDef> classifiers,
            Map<String, DecisionDef> decisions) {
        switch (b.typeOf()) {
            case "string":
                return SymType.STRING;
            case "number":
                return SymType.NUMBER;
            case "list":
                return SymType.listOf(SymType.UNKNOWN);
            case "literal":
                return "number".equals(b.typeName())
                        ? SymType.NUMBER
                        : "string".equals(b.typeName()) ? SymType.STRING : SymType.UNKNOWN;
            case "agent":
                {
                    SymType expecting = expecting(l, b);
                    if (expecting != null) return expecting;
                    AgentDef a = agents.get(b.typeName());
                    if (a == null) return SymType.UNKNOWN;
                    return a.getOutputSchema() == null
                            ? SymType.STRING
                            : SymType.of(a.getOutputSchema());
                }
            case "classifier":
                {
                    IntentClassifierDef c = classifiers.get(b.typeName());
                    return c == null ? SymType.UNKNOWN : classifierRecord(c);
                }
            case "decision":
                {
                    DecisionDef d = decisions.get(b.typeName());
                    return d == null ? SymType.UNKNOWN : SymType.enumOf(d.getChoices());
                }
            default:
                return SymType.UNKNOWN;
        }
    }

    /** The {@code expecting { ... }} of the delegate that makes this variable, when it parsed. */
    private static SymType expecting(Loaded l, FileIndexer.RawBinding b) {
        SymType[] found = {null};
        for (WorkflowDef w : l.parse().script().getWorkflows()) {
            if (!w.getName().equals(b.workflow())) continue;
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof DelegateStmt d
                                && d.getLine() == b.statementLine()
                                && d.getExpecting() != null)
                            found[0] = SymType.of(d.getExpecting());
                    });
        }
        return found[0];
    }

    /**
     * The record an {@code intentclassify} binds: one field per question, and the reserved ones.
     */
    static SymType classifierRecord(IntentClassifierDef c) {
        Map<String, SymType> fields = new LinkedHashMap<>();
        for (IntentClassifierDef.Question q : c.getQuestions()) {
            fields.put(
                    q.name(),
                    q.kind() == IntentClassifierDef.Kind.ASK
                            ? SymType.YESNO
                            : SymType.enumOf(c.possibleLabels(q)));
        }
        fields.put("confidence", SymType.NUMBER);
        fields.put("calibrated", SymType.YESNO);
        fields.put("backend", SymType.STRING);
        fields.put("model", SymType.STRING);
        return SymType.object(fields);
    }
}
