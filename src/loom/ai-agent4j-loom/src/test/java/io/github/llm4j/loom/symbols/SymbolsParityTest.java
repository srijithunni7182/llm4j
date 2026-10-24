package io.github.llm4j.loom.symbols;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * R20 of spec 10 against the real corpus: on every script in the repository that the language's
 * parser reads, the index defines exactly what the parser does, every range reads back as its name,
 * and every agent, workflow, classifier, decision, checkpoint and variable the script uses is found
 * (a name the index cannot find would be a name the index misses in a rename).
 */
class SymbolsParityTest {

    private static List<Path> corpus() throws Exception {
        Path repo = Path.of("../../..").toAbsolutePath().normalize();
        try (Stream<Path> all = Files.walk(repo)) {
            return all.filter(p -> p.toString().endsWith(".loom") && Files.isRegularFile(p))
                    .filter(
                            p ->
                                    !p.toString().contains("/target/")
                                            && !p.toString().contains("/node_modules/"))
                    .sorted()
                    .toList();
        }
    }

    /** Scripts made to fail the checks: a name in them is meant not to resolve. */
    private static boolean deliberatelyWrong(Path file) {
        String p = file.toString();
        return p.endsWith("graph/undefined_agent.loom")
                || p.endsWith("graph/unresolved_call.loom")
                || p.endsWith("ctk/scripts/failing_test.loom")
                || p.endsWith("ctk/scripts/intervention_rewind_undeclared.loom");
    }

    /** A flows file whose agents are defined by the script that imports it. */
    private static boolean importedByAnother(Path file, List<Path> corpus) {
        java.util.regex.Pattern importLine =
                java.util.regex.Pattern.compile("(?m)^\\s*import\\s+\"([^\"]+)\"");
        for (Path other : corpus) {
            try {
                java.util.regex.Matcher m = importLine.matcher(Files.readString(other));
                while (m.find())
                    if (other.getParent().resolve(m.group(1)).normalize().equals(file.normalize()))
                        return true;
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return false;
    }

    private static Set<String> names(String kind, LoomScript s) {
        Set<String> out = new TreeSet<>();
        switch (kind) {
            case "agent" -> s.getAgents().forEach(a -> out.add(a.getName()));
            case "workflow" -> s.getWorkflows().forEach(a -> out.add(a.getName()));
            case "tool" -> s.getTools().forEach(a -> out.add(a.getName()));
            case "classifier" -> s.getIntentClassifiers().forEach(a -> out.add(a.getName()));
            case "provider" -> s.getProviders().forEach(a -> out.add(a.getName()));
            case "persona" -> s.getPersonas().forEach(a -> out.add(a.getName()));
            case "decision" -> s.getDecisions().forEach(a -> out.add(a.getName()));
            default -> throw new IllegalArgumentException(kind);
        }
        return out;
    }

    @Test
    void theIndexDefinesWhatTheParserDefinesOnEveryScriptOfTheRepository() throws Exception {
        List<String> differences = new ArrayList<>();
        int checked = 0;
        List<Path> corpus = corpus();
        for (Path file : corpus) {
            String text = Files.readString(file);
            LoomScript parsed;
            try {
                parsed = new LoomParser(new Lexer(text).tokenize()).parseScript();
            } catch (RuntimeException e) {
                continue; // a script that is wrong on purpose: the recovery tests cover those
            }
            checked++;
            SymbolIndex index = new SymbolService().index(file);
            for (String kind :
                    List.of(
                            "agent",
                            "workflow",
                            "tool",
                            "classifier",
                            "provider",
                            "persona",
                            "decision")) {
                Set<String> inIndex = new TreeSet<>();
                for (SymbolIndex.Definition d : index.definitions())
                    if (d.kind().equals(kind)
                            && d.file().equals(file.toAbsolutePath().normalize().toString()))
                        inIndex.add(d.name());
                Set<String> inParser = names(kind, parsed);
                if (!inIndex.equals(inParser))
                    differences.add(
                            file + " " + kind + ": index " + inIndex + " parser " + inParser);
            }
            String[] lines = text.split("\n", -1);
            for (SymbolIndex.Definition d : index.definitions()) {
                if (!d.file().equals(file.toAbsolutePath().normalize().toString())) continue;
                if (d.kind().equals("prompt")) continue;
                String got = SymbolsFixture.slice(text, d.range());
                if (!got.equals(d.name()))
                    differences.add(file + " range of " + d.key() + " reads '" + got + "'");
            }
            for (SymbolIndex.Reference r : index.references()) {
                if (!r.file().equals(file.toAbsolutePath().normalize().toString())) continue;
                if (r.resolved() || deliberatelyWrong(file) || importedByAnother(file, corpus))
                    continue;
                if (Set.of("agent", "workflow", "classifier", "decision", "checkpoint", "binding")
                        .contains(r.kind()))
                    differences.add(
                            file
                                    + " "
                                    + r.to()
                                    + " ("
                                    + r.role()
                                    + ") line "
                                    + (r.range().line() + 1)
                                    + " is not found");
            }
        }
        assertThat(checked).isGreaterThan(20);
        assertThat(differences).isEmpty();
    }
}
