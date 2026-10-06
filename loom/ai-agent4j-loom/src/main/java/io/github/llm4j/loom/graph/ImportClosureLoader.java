package io.github.llm4j.loom.graph;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads a script and its imports without merging them, so every definition stays tied to its file. Imports are
 * resolved relative to the importing file, as {@code LoomLoader} does. A missing file, a syntax error or a cycle is
 * reported as a diagnostic and the rest of the closure is still loaded.
 */
public final class ImportClosureLoader {

    private static final Pattern PARSE_LINE = Pattern.compile("line (\\d+)");

    public ImportClosure load(Path entryFile) {
        return new Run(entryFile.toAbsolutePath().normalize()).run();
    }

    private static final class Run {
        private final Path entry;
        private final Map<Path, LoomScript> scripts = new LinkedHashMap<>();
        private final Map<Path, List<Path>> directImports = new LinkedHashMap<>();
        private final Set<Path> discovery = new LinkedHashSet<>();
        private final Set<Path> attempted = new HashSet<>();
        private final List<Path> visiting = new ArrayList<>();
        private final List<Diagnostic> diagnostics = new ArrayList<>();

        Run(Path entry) {
            this.entry = entry;
        }

        ImportClosure run() {
            visit(entry, null, 0);
            Map<Path, List<Path>> resolved = new LinkedHashMap<>();
            for (Path file : scripts.keySet()) {
                List<Path> kept = new ArrayList<>();
                for (Path imported : directImports.getOrDefault(file, List.of())) {
                    if (scripts.containsKey(imported) && !kept.contains(imported)) {
                        kept.add(imported);
                    }
                }
                resolved.put(file, kept);
            }
            List<Path> order = new ArrayList<>();
            for (Path file : discovery) {
                if (scripts.containsKey(file)) {
                    order.add(file);
                }
            }
            return new ImportClosure(entry, scripts, resolved, order, diagnostics);
        }

        private void visit(Path file, Path importer, int importLine) {
            if (visiting.contains(file)) {
                diagnostics.add(Diagnostic.warning(
                        importer.toString(), importLine, "Import cycle skipped: " + cycle(file) + "."));
                return;
            }
            if (!attempted.add(file)) {
                return;
            }
            discovery.add(file);
            LoomScript script = read(file, importer, importLine);
            if (script == null) {
                return;
            }
            visiting.add(file);
            List<Path> imports = new ArrayList<>();
            for (int i = 0; i < script.getImports().size(); i++) {
                Path target = file.getParent().resolve(script.getImports().get(i)).normalize();
                int line = script.getImportLines().get(i);
                imports.add(target);
                if (!Files.exists(target)) {
                    diagnostics.add(Diagnostic.error(file.toString(), line, "Imported file not found: " + target));
                    continue;
                }
                visit(target, file, line);
            }
            visiting.remove(file);
            directImports.put(file, imports);
            scripts.put(file, script);
        }

        private LoomScript read(Path file, Path importer, int importLine) {
            try {
                return new LoomParser(new Lexer(Files.readString(file)).tokenize()).parseScript();
            } catch (IOException e) {
                diagnostics.add(Diagnostic.error(
                        (importer == null ? file : importer).toString(), importLine, "Cannot read " + file + ": " + e.getMessage()));
            } catch (RuntimeException e) {
                diagnostics.add(Diagnostic.error(file.toString(), lineOf(e.getMessage()), message(e)));
            }
            return null;
        }

        private String cycle(Path back) {
            int from = visiting.indexOf(back);
            List<String> names = new ArrayList<>();
            for (Path p : visiting.subList(from, visiting.size())) {
                names.add(p.getFileName().toString());
            }
            names.add(back.getFileName().toString());
            return String.join(" → ", names);
        }

        private static int lineOf(String message) {
            if (message == null) {
                return 0;
            }
            Matcher m = PARSE_LINE.matcher(message);
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        }

        private static String message(RuntimeException e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }
}
