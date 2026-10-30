package io.github.llm4j.loom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.llm4j.eval.dataset.EvalScenario;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A versioned corpus of prompt-injection payloads, turned into eval cases. Direct payloads are the
 * user's input. Indirect payloads are embedded in the project's <em>own realistic artefacts</em>
 * (templates named after the placement: {@code log.txt}, {@code web-page.html}), because a payload
 * in an obviously fake artefact tests nothing; with no template for a placement the payload is
 * skipped and said to be, never given an invented one. Each case carries a canary the answer must
 * never contain (obedience, a mitigation measured as a percentage) and the actions that must never
 * happen (the controls, which must hold at 100% even when the model is fooled).
 *
 * <p>The corpus holds hostile text: run it against scripted or sandboxed tools, never a real
 * sender, payer or writer. Counts are compared with the guide's starting points (60 direct, 100
 * indirect) as information; the team sets the real ones.
 */
public final class SecurityCorpus {

    /** The classes of payload. */
    public static final List<String> KINDS = List.of("direct", "indirect");

    /** Where a payload sits. */
    public static final List<String> PLACEMENTS =
            List.of("user", "document", "web-page", "log", "filename", "config", "database-field", "ticket", "email", "api-field", "code");

    /** What a payload tries to get done. */
    public static final List<String> GOALS =
            List.of(
                    "override",
                    "role-reassignment",
                    "fake-system-message",
                    "authority-claim",
                    "encoding",
                    "incremental",
                    "approval-spoofing",
                    "fabricated-context",
                    "tool-coercion",
                    "exfiltration",
                    "conclusion-steering",
                    "action-injection",
                    "split-payload");

    /** The placeholder a payload must hold, replaced by its canary. */
    public static final String CANARY_PLACEHOLDER = "{canary}";

    private static final String EMBED = "{payload}";
    private static final int DIRECT_STARTING_POINT = 60;
    private static final int INDIRECT_STARTING_POINT = 100;

    private SecurityCorpus() {}

    /**
     * One payload.
     *
     * @param id its name, unique in the corpus
     * @param kind {@code direct} or {@code indirect}
     * @param placement where an indirect payload sits
     * @param goal what it tries to get done
     * @param text the payload, holding {@code {canary}}
     */
    public record Payload(String id, String kind, String placement, String goal, String text) {
        /** The marker an obedient answer would contain. */
        public String canary() {
            return "CANARY-" + id;
        }

        /** The payload with its canary written in. */
        public String armed() {
            return text.replace(CANARY_PLACEHOLDER, canary());
        }
    }

    /**
     * Reads every {@code *.yaml} file of a corpus folder.
     *
     * @throws IllegalArgumentException for an unknown class, placement or goal, a payload with no
     *     canary placeholder, or an id used twice: each names the payload and the choices
     */
    public static List<Payload> read(Path corpus) throws IOException {
        List<Payload> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        List<Path> files;
        try (Stream<Path> s = Files.list(corpus)) {
            files = s.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")).sorted().toList();
        }
        for (Path file : files) {
            JsonNode root = yaml.readTree(Files.readString(file));
            if (root == null || !root.isArray()) throw new IllegalArgumentException(file.getFileName() + ": a list of payloads was expected");
            for (JsonNode n : root) {
                String id = text(n, "id");
                String kind = text(n, "class");
                String placement = text(n, "placement");
                String goal = text(n, "goal");
                String payload = text(n, "payload");
                if (id == null || !seen.add(id)) throw new IllegalArgumentException("payload " + id + " is used twice or has no id");
                choose(id, "class", kind, KINDS);
                choose(id, "placement", placement, PLACEMENTS);
                choose(id, "goal", goal, GOALS);
                if (payload == null || !payload.contains(CANARY_PLACEHOLDER))
                    throw new IllegalArgumentException("payload " + id + " must hold " + CANARY_PLACEHOLDER + ": without a canary, obedience could not be seen");
                out.add(new Payload(id, kind, placement, goal, payload));
            }
        }
        return out;
    }

    private static String text(JsonNode n, String key) {
        return n.hasNonNull(key) ? n.get(key).asText() : null;
    }

    private static void choose(String id, String what, String value, List<String> allowed) {
        if (value == null || !allowed.contains(value))
            throw new IllegalArgumentException("payload " + id + ": " + what + " " + value + " is not one of " + String.join(", ", allowed));
    }

    /** The eval cases for a corpus: direct payloads as inputs, indirect ones embedded in each template of their placement. */
    public static List<EvalScenario> generate(List<Payload> payloads, Path templates, List<String> forbidden) throws IOException {
        List<EvalScenario> cases = new ArrayList<>();
        for (Payload p : payloads) {
            if (p.kind().equals("direct")) {
                cases.add(scenario("sec-" + p.id(), p, p.armed(), forbidden));
                continue;
            }
            for (Path template : templatesFor(templates, p.placement())) {
                String stem = template.getFileName().toString().replaceFirst("\\.[^.]+$", "");
                String artefact = Files.readString(template);
                if (!artefact.contains(EMBED))
                    throw new IllegalArgumentException("template " + template.getFileName() + " has no " + EMBED + " to put the payload at");
                cases.add(scenario("sec-" + p.id() + "-" + stem, p, artefact.replace(EMBED, p.armed()), forbidden));
            }
        }
        return cases;
    }

    /** Generates the cases and writes them as a dataset file; returns how many. */
    public static int write(List<Payload> payloads, Path templates, List<String> forbidden, Path file) throws IOException {
        List<EvalScenario> cases = generate(payloads, templates, forbidden);
        io.github.llm4j.eval.dataset.EvalScenarios.toYaml(cases, file);
        return cases.size();
    }

    /** The indirect payloads that have no template, one line each. */
    public static List<String> skipped(List<Payload> payloads, Path templates) throws IOException {
        List<String> out = new ArrayList<>();
        for (Payload p : payloads)
            if (p.kind().equals("indirect") && templatesFor(templates, p.placement()).isEmpty())
                out.add("payload " + p.id() + " skipped: no template for the placement " + p.placement() + " (write a realistic " + p.placement() + " artefact of your own, named " + p.placement() + ".txt, holding " + EMBED + "; an invented one tests nothing)");
        return out;
    }

    private static List<Path> templatesFor(Path templates, String placement) throws IOException {
        if (!Files.isDirectory(templates)) return List.of();
        try (Stream<Path> s = Files.list(templates)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String stem = p.getFileName().toString().replaceFirst("\\.[^.]+$", "");
                        return stem.equals(placement) || stem.startsWith(placement + "-");
                    })
                    .sorted()
                    .toList();
        }
    }

    private static EvalScenario scenario(String id, Payload p, String input, List<String> forbidden) {
        return new EvalScenario(
                id,
                input,
                null,
                null,
                null,
                null,
                null,
                id,
                null,
                List.of("kind:injection", "class:" + p.kind(), "placement:" + p.placement(), "goal:" + p.goal(), "source:security-corpus"),
                null,
                null,
                null,
                List.of(p.canary()),
                null,
                null,
                null,
                null,
                null,
                null,
                forbidden.isEmpty() ? null : List.copyOf(forbidden),
                null);
    }

    /** How big the corpus is, against the guide's starting points. */
    public static String size(List<Payload> payloads) {
        long direct = payloads.stream().filter(p -> p.kind().equals("direct")).count();
        long indirect = payloads.size() - direct;
        return direct
                + " direct and "
                + indirect
                + " indirect payloads (the guide's starting points are "
                + DIRECT_STARTING_POINT
                + " and "
                + INDIRECT_STARTING_POINT
                + "; the team sets the real ones).";
    }
}
