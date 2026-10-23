package io.github.llm4j.loom.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.review.fact.SecretShapes;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * The model's side of a generation, saved or replayed: one JSON line for each call with a
 * fingerprint of what was asked, the reply and the tokens. A saved transcript holds no request text
 * and nothing shaped like a key, so it can be attached to a bug report; a replay answers the n-th
 * call with the n-th reply and says plainly when what is asked is no longer what was recorded.
 */
public final class Transcript {

    private Transcript() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One recorded call. */
    public record Entry(String request, String reply, int promptTokens, int completionTokens) {}

    /** A client that asks {@code inner} and keeps every exchange to write with {@link #save}. */
    public static final class Recording implements LLMClient {
        private final LLMClient inner;
        private final List<Entry> entries = new ArrayList<>();

        public Recording(LLMClient inner) {
            this.inner = inner;
        }

        @Override
        public LLMResponse chat(LLMRequest request) {
            LLMResponse r = inner.chat(request);
            int in = r.getTokenUsage() == null ? 0 : r.getTokenUsage().getPromptTokens();
            int out = r.getTokenUsage() == null ? 0 : r.getTokenUsage().getCompletionTokens();
            entries.add(new Entry(fingerprint(request), r.getContent(), in, out));
            return r;
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            return Stream.of(chat(request));
        }

        public List<Entry> entries() {
            return List.copyOf(entries);
        }

        /** Writes the transcript; refuses when a reply holds something shaped like a key. */
        public void save(Path file) throws IOException {
            StringBuilder out = new StringBuilder();
            for (Entry e : entries) {
                if (SecretShapes.in(e.reply()))
                    throw new IOException(
                            "A reply holds something shaped like a key, so the transcript is not written.");
                ObjectNode n = JSON.createObjectNode();
                n.put("request", e.request()).put("reply", e.reply());
                n.put("promptTokens", e.promptTokens())
                        .put("completionTokens", e.completionTokens());
                out.append(JSON.writeValueAsString(n)).append('\n');
            }
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Answers from a transcript; throws {@link IllegalStateException} when it runs out or differs.
     */
    public static final class Replay implements LLMClient {
        private final List<Entry> entries;
        private int next;

        public Replay(List<Entry> entries) {
            this.entries = List.copyOf(entries);
        }

        public static Replay read(Path file) throws IOException {
            List<Entry> list = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode n = JSON.readTree(line);
                list.add(
                        new Entry(
                                n.path("request").asText(),
                                n.path("reply").asText(),
                                n.path("promptTokens").asInt(),
                                n.path("completionTokens").asInt()));
            }
            return new Replay(list);
        }

        @Override
        public synchronized LLMResponse chat(LLMRequest request) {
            if (next >= entries.size())
                throw new IllegalStateException(
                        "The transcript holds "
                                + entries.size()
                                + " calls and the generator made another: it asks more than when this was recorded.");
            Entry e = entries.get(next);
            String asked = fingerprint(request);
            if (!e.request().equals(asked))
                throw new IllegalStateException(
                        "Call "
                                + (next + 1)
                                + " is not what the transcript recorded (it was "
                                + e.request()
                                + ", now "
                                + asked
                                + "): the prompt, the plan or the files changed since it was recorded.");
            next++;
            return LLMResponse.builder()
                    .content(e.reply())
                    .model("replay")
                    .tokenUsage(
                            e.promptTokens(),
                            e.completionTokens(),
                            e.promptTokens() + e.completionTokens())
                    .build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            return Stream.of(chat(request));
        }

        public int used() {
            return next;
        }
    }

    /** A short fingerprint of everything a request says to the model. */
    public static String fingerprint(LLMRequest request) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            request.getMessages()
                    .forEach(
                            m -> {
                                d.update(
                                        String.valueOf(m.getRole())
                                                .getBytes(StandardCharsets.UTF_8));
                                d.update((byte) 0);
                                d.update(
                                        String.valueOf(m.getContent())
                                                .getBytes(StandardCharsets.UTF_8));
                                d.update((byte) 0);
                            });
            return HexFormat.of().formatHex(d.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
