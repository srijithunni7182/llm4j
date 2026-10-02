package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.autonomy.MutableClock;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A secret in a tool option and in a case's values reaches no file the feature writes and no line it prints (spec loom-earned-autonomy R8.6). */
class AutonomySweepTest {

    @TempDir
    Path dir;

    static final String SECRET = "https://hooks.example.com/T9/zq-SECRET-7f3a91";

    @Test
    @Tag("EA-V8.6")
    void aSecretInAToolOptionAndInACasesValuesIsFoundNowhereTheFeatureWritesOrPrints() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-01T00:00:00Z"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String source = io.github.llm4j.loom.autonomy.Scripts2Access.refund("")
                .replace("workflow Triage", "tool Hook { use: webhook url: env.HOOK_URL }\nworkflow Triage")
                .replace("agent Summarizer", "agent Summarizer")
                .replace("agent Triager {", "agent Triager {\n guard { pii: mask }");
        File script = dir.resolve("refund.loom").toFile();
        Files.writeString(script.toPath(), source);
        File candidate = dir.resolve("stricter.loom").toFile();
        Files.writeString(candidate.toPath(), source.replace("You are Triager.", "You are a stricter Triager."));
        Path store = dir.resolve("store");
        LLMClient client = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                // a model that repeats everything it was told, as a worst case
                var m = request.getMessages();
                String last = m.get(m.size() - 1).getContent().replace("\"", "'").replace("\n", " ");
                return LLMResponse.builder().content("```json\n{\"choice\": \"approve\", \"reasoning\": \"I saw: " + last + "\"}\n```").model("m").tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
        WeaveEnv env = new WeaveEnv(m -> client, q -> "approve", new PrintStream(out, true), new PrintStream(err, true), clock, d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), name -> name.equals("HOOK_URL") ? SECRET : System.getenv(name));

        for (int i = 0; i < 8; i++) {
            Map<String, String> in = new LinkedHashMap<>();
            in.put("ticket", "T-" + i);
            in.put("tier", "gold");
            in.put("amount", String.valueOf(20 + i));
            in.put("reason", "the customer pasted " + SECRET + " into the form and mailed ada@example.com");
            in.put("customer_since", "2020");
            WeaveCLI.run(script, null, "Triage", in, null, null, null, null, dir.resolve("runs/c" + i), store, false, false, "text", null, env);
            clock.advance(Duration.ofHours(1));
        }
        ReplayCommand replay = new ReplayCommand();
        replay.script = script;
        replay.candidate = candidate;
        replay.decision = "Refund";
        replay.store = store.toFile();
        replay.seed = 1;
        replay.repeat = 1;
        replay.format = "md";
        assertThat(ReplayCommand.replay(replay, env)).isZero();
        replay.format = "json";
        assertThat(ReplayCommand.replay(replay, env)).isZero();
        AutonomyCommands.status(store, null, null, false, clock, new PrintStream(out, true), new PrintStream(err, true));
        AutonomyCommands.status(store, null, null, true, clock, new PrintStream(out, true), new PrintStream(err, true));
        AutonomyCommands.history(store, null, new PrintStream(out, true), new PrintStream(err, true));
        AutonomyCommands.move(store, "Refund", "gold", "suggest", false, false, "check", null, null, env);

        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        assertThat(files.stream().map(p -> p.getFileName().toString())).contains("ledger.jsonl", "levels.json", "log.jsonl", "report.md", "report.json", "journal.json", "run.json");
        // what the feature writes: its ledger, levels, audit and replays whole; and from each run journal the keys it adds (the rest of the journal is the run's own record)
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        int checkedKeys = 0;
        for (Path f : files) {
            String name = f.getFileName().toString();
            String text = Files.readString(f);
            if (name.equals("journal.json")) {
                Map<?, ?> entries = json.readValue(text, Map.class);
                for (Map.Entry<?, ?> e : entries.entrySet()) {
                    String key = String.valueOf(e.getKey());
                    if (key.contains("#decide-") || key.endsWith("#level")) {
                        checkedKeys++;
                        assertThat(String.valueOf(e.getValue())).as(dir.relativize(f) + " " + key).doesNotContain("zq-SECRET-7f3a91").doesNotContain("ada@example.com");
                    }
                }
            } else if (!name.equals("run.json")) {
                assertThat(text).as(dir.relativize(f).toString()).doesNotContain(SECRET).doesNotContain("zq-SECRET-7f3a91").doesNotContain("ada@example.com");
            }
        }
        assertThat(checkedKeys).isGreaterThan(8);
        assertThat(out.toString()).doesNotContain("zq-SECRET-7f3a91").doesNotContain("ada@example.com");
        assertThat(err.toString()).as("the trace").doesNotContain("zq-SECRET-7f3a91");
    }
}
