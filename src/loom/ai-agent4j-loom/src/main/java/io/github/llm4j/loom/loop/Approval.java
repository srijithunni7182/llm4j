package io.github.llm4j.loom.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.github.llm4j.loom.wtest.TestFile;
import io.github.llm4j.loom.wtest.TestRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The trust of a captured case. It is {@code observed} (this is what happened, which may have been
 * wrong) until a person approves it (this is what should happen) or retires it (no longer
 * applicable). Only approved cases fail a gate. Who and when are recorded; it is a review aid, not
 * a security control.
 */
public final class Approval {

    private Approval() {}

    private static final YAMLMapper YAML =
            YAMLMapper.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The new state of a file. */
    public record Changed(String status, String by, String at) {}

    /**
     * Approves a case. A case that does not pass now is refused unless {@code amend} changes what
     * it expects in the same step (approving a failing case is saying the script is wrong, which
     * belongs in a visible commit). {@code amend} keys are {@code final} and {@code result}.
     */
    public static Changed approve(Path file, String by, String why, Map<String, String> amend)
            throws Exception {
        TestFile f = captured(file);
        if (!amend.isEmpty()) amend(file, amend);
        List<String> failures = failures(file);
        if (!failures.isEmpty())
            throw new IllegalArgumentException(
                    file.getFileName()
                            + " does not pass against the script as it is: "
                            + String.join("; ", failures)
                            + ". Approving it would say the script is wrong. Amend what it"
                            + " expects in the same step (--amend final=..., --amend result=...),"
                            + " or fix the script.");
        return mark(file, f, "approved", by, why);
    }

    public static Changed retire(Path file, String by, String why) throws Exception {
        return mark(file, captured(file), "retired", by, why);
    }

    private static TestFile captured(Path file) {
        TestFile f = TestFile.read(file);
        if (!f.problems().isEmpty())
            throw new IllegalArgumentException(String.join("; ", f.problems()));
        if (f.captured() == null)
            throw new IllegalArgumentException(
                    file.getFileName()
                            + " was not made by weave capture (it has no captured: block).");
        return f;
    }

    private static List<String> failures(Path file) throws Exception {
        var suite = TestRunner.load(null, List.of(file));
        if (!suite.problems().isEmpty()) return suite.problems();
        return TestRunner.run(suite, null, false, false).stream()
                .flatMap(r -> r.failures().stream())
                .toList();
    }

    private static void amend(Path file, Map<String, String> amend) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String head =
                text.lines().takeWhile(l -> l.startsWith("#")).reduce("", (a, b) -> a + b + "\n");
        ObjectNode root = (ObjectNode) YAML.readTree(text);
        JsonNode cases = root.get("cases");
        for (JsonNode c : cases) {
            ObjectNode expect =
                    ((ObjectNode) c).has("expect") && c.get("expect").isObject()
                            ? (ObjectNode) c.get("expect")
                            : ((ObjectNode) c).putObject("expect");
            amend.forEach(
                    (k, v) -> {
                        if (!k.equals("final") && !k.equals("result"))
                            throw new IllegalArgumentException(
                                    "--amend takes final=... or result=..., not " + k);
                        expect.put(k, v);
                    });
        }
        Files.writeString(file, head + YAML.writeValueAsString(root), StandardCharsets.UTF_8);
    }

    private static final Pattern STATUS = Pattern.compile("(?m)^(\\s{2})status:.*$");

    /** Changes the state in the text, keeping every other line (and comment) as it was. */
    private static Changed mark(Path file, TestFile f, String status, String by, String why)
            throws IOException {
        String at = Instant.now().toString();
        String text = Files.readString(file, StandardCharsets.UTF_8);
        // an earlier approval or retirement is replaced, not stacked
        text = text.replaceAll("(?m)^  (?:approved|retired)(?:By|At|Why):.*\\n", "");
        Matcher m = STATUS.matcher(text);
        if (!m.find())
            throw new IllegalArgumentException(file.getFileName() + ": captured: has no status.");
        String verb = status.equals("approved") ? "approved" : "retired";
        String lines =
                "  status: "
                        + status
                        + "\n  "
                        + verb
                        + "By: "
                        + quote(by)
                        + "\n  "
                        + verb
                        + "At: "
                        + at
                        + (why == null || why.isBlank()
                                ? ""
                                : "\n  " + verb + "Why: " + quote(why));
        String out = text.substring(0, m.start()) + lines + text.substring(m.end());
        Files.writeString(file, out, StandardCharsets.UTF_8);
        return new Changed(status, by, at);
    }

    private static String quote(String s) {
        return JSON.valueToTree(s).toString();
    }

    /** How many captured cases in these files wait for a person, and how many are approved. */
    public static Map<String, Integer> counts(List<TestFile> files) {
        int observed = 0, approved = 0, retired = 0;
        for (TestFile f : files) {
            String s = f.capturedStatus();
            if (s == null) continue;
            switch (s) {
                case "observed" -> observed++;
                case "approved" -> approved++;
                case "retired" -> retired++;
                default -> {}
            }
        }
        return Map.of("observed", observed, "approved", approved, "retired", retired);
    }
}
