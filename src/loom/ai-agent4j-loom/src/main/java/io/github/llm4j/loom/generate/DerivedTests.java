package io.github.llm4j.loom.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.github.llm4j.loom.wtest.TestRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Puts the tool-made tests into a generated project and sorts out the cases the model wrote.
 *
 * <p>The tool writes {@code tests/derived.test.yaml} itself ({@link TestSynth}). A case the model
 * wrote either holds or it does not: one that holds stays; one that does not (the script and the
 * plan disagree, or the case is wrong) is taken out of its file and written to {@code
 * tests/LEFT_OUT.md} with what differed, so the generation is not stopped by it but the developer
 * sees it. Nothing is hidden: the file says which cases are not run and why.
 */
final class DerivedTests {

    private DerivedTests() {}

    static final String DERIVED = "derived.test.yaml";
    static final String LEFT_OUT = "LEFT_OUT.md";
    private static final YAMLMapper YAML = new YAMLMapper();

    /** Whether the tool-made file is already in the project. */
    static boolean present(Workspace ws) {
        return Files.isRegularFile(testsDir(ws).resolve(DERIVED));
    }

    /** What was done. */
    record Done(TestSynth.Derived derived, List<String> leftOut) {}

    /**
     * Writes the tool-made file, and takes the cases that do not hold out of the model's files.
     * Does nothing when the script cannot be loaded (G1's to report).
     */
    static Done refresh(Workspace ws) throws IOException {
        Path script = ws.script();
        if (!Files.isRegularFile(script)) return new Done(null, List.of());
        Path testsDir = testsDir(ws);
        Path derivedFile = testsDir.resolve(DERIVED);
        String pointer = testsDir.relativize(script).toString().replace('\\', '/');
        TestSynth.Derived derived = TestSynth.derive(script, pointer);
        if (derived == null) return new Done(null, List.of());
        Files.createDirectories(testsDir);
        Files.writeString(derivedFile, derived.yaml(), StandardCharsets.UTF_8);
        ws.register(rel(ws, derivedFile));

        List<String> leftOut = new ArrayList<>();
        for (Path f : ws.testFiles()) {
            if (f.getFileName().toString().equals(DERIVED)) continue;
            leftOut.addAll(sort(ws, script, f));
        }
        Path note = testsDir.resolve(LEFT_OUT);
        if (leftOut.isEmpty()) {
            if (Files.deleteIfExists(note)) ws.unregister(rel(ws, note));
        } else {
            Files.writeString(note, noteText(leftOut), StandardCharsets.UTF_8);
            ws.register(rel(ws, note));
        }
        return new Done(derived, leftOut);
    }

    /** Keeps the cases of one model-written file that hold; returns what was taken out. */
    private static List<String> sort(Workspace ws, Path script, Path file) throws IOException {
        List<String> out = new ArrayList<>();
        String name = file.getFileName().toString();
        TestRunner.Suite suite;
        try {
            suite = TestRunner.load(script, List.of(file));
        } catch (Exception e) {
            return remove(ws, file, "the file could not be read: " + e.getMessage());
        }
        if (!suite.problems().isEmpty())
            return remove(ws, file, String.join("; ", suite.problems()));
        List<TestRunner.CaseResult> results = TestRunner.run(suite, null, false, false);
        Set<String> failing = new HashSet<>();
        for (TestRunner.CaseResult r : results)
            if (!r.passed()) {
                failing.add(r.name());
                out.add(name + ": \"" + r.name() + "\": " + String.join("; ", r.failures()));
            }
        if (failing.isEmpty()) return out;
        JsonNode root = YAML.readTree(Files.readString(file, StandardCharsets.UTF_8));
        ArrayNode kept = YAML.createArrayNode();
        for (JsonNode c : root.path("cases"))
            if (!failing.contains(c.path("name").asText())) kept.add(c);
        if (kept.isEmpty()) {
            Files.delete(file);
            ws.unregister(rel(ws, file));
        } else {
            ((ObjectNode) root).set("cases", kept);
            Files.writeString(file, YAML.writeValueAsString(root), StandardCharsets.UTF_8);
        }
        return out;
    }

    private static List<String> remove(Workspace ws, Path file, String why) throws IOException {
        String name = file.getFileName().toString();
        Files.delete(file);
        ws.unregister(rel(ws, file));
        return List.of(name + ": the whole file was left out: " + why);
    }

    private static Path testsDir(Workspace ws) {
        boolean maven = ws.script().toString().replace('\\', '/').contains("src/main/resources/");
        return ws.dir()
                .resolve(
                        Writer.placed(
                                maven ? Writer.Layout.MAVEN : Writer.Layout.FLAT,
                                "tests/" + DERIVED))
                .getParent();
    }

    private static String rel(Workspace ws, Path p) {
        return ws.dir().relativize(p).toString().replace('\\', '/');
    }

    private static String noteText(List<String> leftOut) {
        StringBuilder sb =
                new StringBuilder(
                        "# Test cases left out\n\n"
                                + "`weave generate` wrote these cases from the plan, but the script does not do what they say, "
                                + "so they are not run. Each is either a mistake in the case or a place where the script "
                                + "does not follow the plan: read them and decide which.\n\n");
        for (String l : leftOut) sb.append("- ").append(l.replace("\n", " ")).append('\n');
        return sb.toString();
    }
}
