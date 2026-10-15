package io.github.llm4j.loom.context;

import io.github.llm4j.context.ContextCompactor;
import io.github.llm4j.context.ContextCompactorProvider;
import io.github.llm4j.context.ContextCompactors;
import io.github.llm4j.context.ProviderContext;
import io.github.llm4j.loom.init.ProjectClasses;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Your own compaction strategies, by the name a script uses. A {@code .loct} file (the sibling of {@code .loot}, which does the same for tools) maps a
 * name to a class, one per line: {@code keep_decisions = com.acme.KeepDecisionsCompactor}. The class implements {@link ContextCompactor} (public
 * no-argument constructor) or {@link ContextCompactorProvider} (when it wants the block's options, or a model). A host can also register one in code.
 */
public final class CompactorRegistry {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");

    /** Something wrong with a {@code .loct} file; {@code line} is 0 for the file as a whole. */
    public record Problem(String file, int line, boolean error, String message) {
        @Override
        public String toString() {
            return file + (line > 0 ? " line " + line : "") + ": " + message;
        }
    }

    /** A mapping as written: for {@code weave audit}, which never loads the class. */
    public record Entry(String name, String className, String file, int line) { }

    private interface Maker {
        ContextCompactor make(Map<String, String> options, ProviderContext context);
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Maker> code = new LinkedHashMap<>();
    private final List<Problem> loadProblems = new ArrayList<>();

    /** Reads a {@code .loct} file; a mapping from a later file replaces an earlier one. Returns what is wrong with it; wrong lines are not added. */
    public List<Problem> load(Path file) {
        List<Problem> problems = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            problems.add(new Problem(file.toString(), 0, true, "cannot be read: " + e.getMessage()));
            loadProblems.addAll(problems);
            return problems;
        }
        Set<String> inThisFile = new TreeSet<>();
        try {
            return read(file, lines, problems, inThisFile);
        } finally {
            loadProblems.addAll(problems);
        }
    }

    private List<Problem> read(Path file, List<String> lines, List<Problem> problems, Set<String> inThisFile) {
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            int hash = raw.indexOf('#');
            String text = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
            if (text.isEmpty()) continue;
            int eq = text.indexOf('=');
            if (eq <= 0 || eq == text.length() - 1) {
                problems.add(new Problem(file.toString(), i + 1, true, "write a strategy as name = com.acme.Class"));
                continue;
            }
            String name = text.substring(0, eq).trim();
            String cls = text.substring(eq + 1).trim();
            if (!NAME.matcher(name).matches()) {
                problems.add(new Problem(file.toString(), i + 1, true, "'" + name + "' is not a usable name: lower case letters, digits and underscores, starting with a letter"));
            } else if (ContextCompactors.names().contains(name)) {
                problems.add(new Problem(file.toString(), i + 1, true, "'" + name + "' is a built-in strategy; choose another name"));
            } else if (!inThisFile.add(name)) {
                problems.add(new Problem(file.toString(), i + 1, true, "'" + name + "' is mapped twice in this file"));
            } else {
                entries.put(name, new Entry(name, cls, file.toString(), i + 1));
                code.remove(name);
            }
        }
        return problems;
    }

    /** Registers a ready-made strategy under a name (it receives no options). */
    public void register(String name, ContextCompactor compactor) {
        checkName(name);
        java.util.Objects.requireNonNull(compactor, "compactor");
        code.put(name, (options, context) -> {
            if (!options.isEmpty()) throw new IllegalArgumentException(name + " takes no options, got: " + String.join(", ", options.keySet()));
            return compactor;
        });
        entries.remove(name);
    }

    /** Registers a provider under a name; it receives the block's options and a context. */
    public void register(String name, ContextCompactorProvider provider) {
        checkName(name);
        java.util.Objects.requireNonNull(provider, "provider");
        code.put(name, provider::create);
        entries.remove(name);
    }

    private static void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches()) throw new IllegalArgumentException("'" + name + "' is not a usable strategy name");
        if (ContextCompactors.names().contains(name)) throw new IllegalArgumentException("'" + name + "' is a built-in strategy; choose another name");
    }

    /** Everything wrong with the files read so far. */
    public List<Problem> loadProblems() {
        return List.copyOf(loadProblems);
    }

    public Set<String> names() {
        Set<String> all = new TreeSet<>(entries.keySet());
        all.addAll(code.keySet());
        return Collections.unmodifiableSet(all);
    }

    public boolean has(String name) {
        return entries.containsKey(name) || code.containsKey(name);
    }

    /** The mappings as written in files (not the ones registered in code). */
    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** True when the strategy takes options (it is a provider); false for a plain strategy; empty when that cannot be told without the class. */
    public Optional<Boolean> takesOptions(String name) {
        if (code.containsKey(name)) return Optional.empty();
        Entry e = entries.get(name);
        if (e == null) return Optional.empty();
        try {
            Class<?> c = Class.forName(e.className(), false, ProjectClasses.loader());
            return Optional.of(ContextCompactorProvider.class.isAssignableFrom(c));
        } catch (ClassNotFoundException | LinkageError ex) {
            return Optional.empty();
        }
    }

    /**
     * What is wrong with the mapped classes, without running any of them: a class that is missing (a warning: it may be built later), of the wrong
     * type, or without a public no-argument constructor.
     */
    public List<Problem> verify() {
        List<Problem> problems = new ArrayList<>();
        for (Entry e : entries.values()) {
            try {
                Class<?> c = Class.forName(e.className(), false, ProjectClasses.loader());
                if (!ContextCompactor.class.isAssignableFrom(c) && !ContextCompactorProvider.class.isAssignableFrom(c)) {
                    problems.add(new Problem(e.file(), e.line(), true, e.className() + " implements neither ContextCompactor nor ContextCompactorProvider"));
                } else {
                    try {
                        c.getConstructor();
                    } catch (NoSuchMethodException ex) {
                        problems.add(new Problem(e.file(), e.line(), true, e.className() + " needs a public constructor without arguments"));
                    }
                }
            } catch (ClassNotFoundException | LinkageError ex) {
                problems.add(new Problem(e.file(), e.line(), false, "class " + e.className() + " is not on the class path (weave --classes, or mvn compile); it must be there to run"));
            }
        }
        return problems;
    }

    /** Builds the strategy. The class is loaded now, not when the file is read. */
    public ContextCompactor create(String name, Map<String, String> options, ProviderContext context) {
        Map<String, String> opts = options == null ? Map.of() : options;
        ProviderContext ctx = context == null ? ProviderContext.none() : context;
        Maker m = code.get(name);
        if (m != null) return m.make(opts, ctx);
        Entry e = entries.get(name);
        if (e == null) throw new IllegalArgumentException("no strategy named '" + name + "' is mapped; mapped: " + String.join(", ", names()));
        try {
            Class<?> c = Class.forName(e.className(), true, ProjectClasses.loader());
            Object instance = c.getConstructor().newInstance();
            if (instance instanceof ContextCompactorProvider p) return p.create(opts, ctx);
            if (instance instanceof ContextCompactor compactor) {
                if (!opts.isEmpty()) throw new IllegalArgumentException(name + " (" + e.className() + ") takes no options, got: " + String.join(", ", opts.keySet()));
                return compactor;
            }
            throw new IllegalArgumentException(e.className() + " implements neither ContextCompactor nor ContextCompactorProvider (" + e.file() + " line " + e.line() + ")");
        } catch (ReflectiveOperationException | LinkageError ex) {
            throw new IllegalArgumentException("cannot create strategy " + name + " from " + e.className() + " (" + e.file() + " line " + e.line() + "): " + ex);
        }
    }
}
