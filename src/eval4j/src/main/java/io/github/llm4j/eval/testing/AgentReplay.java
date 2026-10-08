package io.github.llm4j.eval.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.agent.AgentResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Remembers what an agent produced for a case, so a repeated or resumed evaluation pays only for
 * what is missing. The key covers whatever you pass to {@link #key(String...)}: usually the
 * scenario id, the prompt version, the model and the recorded fixtures. A {@code CURRENT TIME: ...}
 * line is ignored in keys, because personas that embed the clock would otherwise never hit the
 * store.
 *
 * <pre>{@code
 * AgentReplay replay = AgentReplay.at(Path.of("target/eval/replay"));
 * AgentResult r = replay.run(AgentReplay.key(scenario.id(), "v1", model, fixtureText), () -> agent.run(task));
 * }</pre>
 */
public final class AgentReplay {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CLOCK = Pattern.compile("CURRENT TIME[^\\n]*");

    private final Path dir;

    private AgentReplay(Path dir) {
        this.dir = dir;
    }

    public static AgentReplay at(Path dir) {
        return new AgentReplay(dir);
    }

    /** A stable key for these parts. */
    public static String key(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(p == null ? "" : CLOCK.matcher(p).replaceAll("CURRENT TIME"))
                    .append('\u0000');
        }
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True if a run is stored under {@code key}. */
    public boolean has(String key) {
        return Files.exists(file(key));
    }

    /** The stored run for {@code key}, or the result of {@code run} (which is then stored). */
    public AgentResult run(String key, Supplier<AgentResult> run) {
        if (has(key)) {
            return read(key);
        }
        AgentResult r = run.get();
        write(key, r);
        return r;
    }

    private Path file(String key) {
        return dir.resolve(key + ".json");
    }

    public void write(String key, AgentResult r) {
        ObjectNode n = JSON.createObjectNode();
        n.put("answer", r.getFinalAnswer());
        n.put("completed", r.isCompleted());
        n.put("iterations", r.getIterations());
        ArrayNode steps = n.putArray("steps");
        for (AgentResult.AgentStep st : r.getSteps()) {
            ObjectNode o = steps.addObject();
            o.put("thought", st.getThought());
            o.put("action", st.getAction());
            o.put("input", st.getActionInput());
            o.put("observation", st.getObservation());
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(file(key), n.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public AgentResult read(String key) {
        try {
            JsonNode n = JSON.readTree(Files.readString(file(key)));
            AgentResult.Builder b =
                    AgentResult.builder()
                            .finalAnswer(n.path("answer").asText())
                            .completed(n.path("completed").asBoolean())
                            .iterations(n.path("iterations").asInt());
            for (JsonNode o : n.path("steps")) {
                b.addStep(
                        new AgentResult.AgentStep(
                                text(o, "thought"),
                                text(o, "action"),
                                text(o, "input"),
                                text(o, "observation")));
            }
            return b.build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(JsonNode o, String f) {
        return o.hasNonNull(f) ? o.get(f).asText() : null;
    }
}
