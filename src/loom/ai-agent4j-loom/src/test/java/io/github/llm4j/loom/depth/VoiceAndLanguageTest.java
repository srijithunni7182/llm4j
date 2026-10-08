package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoadException;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.runtime.FileRunJournal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan V2: voice and Indic language services (Sarvam on a mock server). */
class VoiceAndLanguageTest {

    @TempDir
    Path dir;

    MockWebServer sarvam;
    final List<RecordedRequest> received = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    volatile boolean ttsFails;
    static final byte[] WAV = "RIFF....WAVEfmt fake".getBytes();

    @BeforeEach
    void start() throws Exception {
        sarvam = new MockWebServer();
        sarvam.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                received.add(r);
                String json = switch (r.getPath()) {
                    case "/translate" -> "{\"translated_text\": \"नमस्ते दुनिया\"}";
                    case "/transliterate" -> "{\"transliterated_text\": \"namaste duniya\"}";
                    case "/text-lid" -> "{\"request_id\": \"r1\", \"language_code\": \"hi-IN\", \"script_code\": \"Deva\"}";
                    case "/text-to-speech" -> ttsFails ? null : "{\"audios\": [\"" + Base64.getEncoder().encodeToString(WAV) + "\"]}";
                    case "/speech-to-text" -> "{\"transcript\": \"what is the weather in Pune\"}";
                    default -> null;
                };
                return json == null ? new MockResponse().setResponseCode(400).setBody("{\"error\": \"bad\"}")
                        : new MockResponse().setBody(json).setHeader("Content-Type", "application/json");
            }
        });
        sarvam.start();
    }

    @AfterEach
    void stop() throws Exception {
        sarvam.shutdown();
    }

    Harness harness() {
        Harness h = new Harness(dir);
        h.env.put("SARVAM_API_KEY", "sk-test");
        h.env.put("SARVAM_BASE_URL", sarvam.url("").toString().replaceAll("/$", ""));
        return h;
    }

    RecordedRequest request(String path) {
        return received.stream().filter(r -> path.equals(r.getPath())).findFirst().orElseThrow();
    }

    @Test
    void v2_1_builtInsNeedTheKey() {
        Harness h = new Harness(dir);
        assertThatThrownBy(() -> h.ready("agent A { model: \"m\" tools: [translate, speak] }"))
                .isInstanceOfSatisfying(LoomLoadException.class, e -> assertThat(e.getMessage())
                        .contains("tool translate: environment variable SARVAM_API_KEY is not set")
                        .contains("tool speak: environment variable SARVAM_API_KEY is not set"));
        harness().ready("agent A { model: \"m\" tools: [translate, transliterate, detect_language, speak, transcribe] }");
    }

    @Test
    void v2_2_textServicesCallSarvam() throws Exception {
        Harness h = harness().answers(
                Harness.call("translate", "{\"text\": \"hello world\", \"target\": \"Hindi\"}"),
                Harness.call("transliterate", "{\"text\": \"नमस्ते दुनिया\", \"target\": \"en-IN\"}"),
                Harness.call("detect_language", "{\"text\": \"नमस्ते दुनिया\"}"),
                Harness.done("done"));
        h.ready("""
                agent Linguist { model: "m" tools: [translate, transliterate, detect_language] }
                workflow Main() { delegate "help" to Linguist -> out }
                """).executeWorkflow("Main", Map.of());
        assertThat(h.task(1)).contains("Observation: नमस्ते दुनिया");
        assertThat(h.task(2)).contains("Observation: namaste duniya");
        assertThat(h.task(3)).contains("Observation: hi-IN");

        RecordedRequest translate = request("/translate");
        assertThat(translate.getHeader("api-subscription-key")).isEqualTo("sk-test");
        assertThat(translate.getBody().readUtf8()).contains("\"input\":\"hello world\"").contains("\"target_language_code\":\"hi-IN\"");
        assertThat(request("/transliterate").getBody().readUtf8()).contains("\"target_language_code\":\"en-IN\"");
        assertThat(request("/text-lid").getBody().readUtf8()).contains("\"input\":\"नमस्ते दुनिया\"");
    }

    @Test
    void declaredToolsTakeDefaultsAndReportMissingArguments() {
        Harness h = harness().answers(
                Harness.call("Hindi", "{\"text\": \"good morning\"}"),
                Harness.call("Hindi", "{}"),
                Harness.call("Loose", "{\"text\": \"x\"}"),
                Harness.done("done"));
        h.env.put("MY_SARVAM", "sk-own");
        h.ready("""
                tool Hindi { use: translate  target: "hi-IN"  api_key: env.MY_SARVAM }
                tool Loose { use: translate }
                agent A { model: "m" tools: [Hindi, Loose] }
                workflow Main() { delegate "go" to A -> out }
                """).executeWorkflow("Main", Map.of());
        assertThat(request("/translate").getHeader("api-subscription-key")).isEqualTo("sk-own");
        assertThat(h.task(2)).contains("Observation: Error: missing argument text");
        assertThat(h.task(3)).contains("Observation: Error: give target");
        assertThat(h.seen()).contains("default hi-IN");
    }

    @Test
    void v2_3_speakWritesAWavAndTranscribeReadsOne() throws Exception {
        Files.createDirectories(dir.resolve("clips"));
        Files.write(dir.resolve("clips/q.wav"), WAV);
        Harness h = harness().answers(
                Harness.call("speak", "{\"text\": \"namaste\", \"language\": \"hi-IN\"}"),
                Harness.call("transcribe", "{\"path\": \"clips/q.wav\"}"),
                Harness.done("done"));
        h.ready("""
                agent Voice { model: "m" tools: [speak, transcribe] }
                workflow Main() { delegate "go" to Voice -> out }
                """).executeWorkflow("Main", Map.of());
        assertThat(h.task(1)).contains("Observation: Saved speech to audio/speech-");
        try (var files = Files.list(dir.resolve("audio"))) {
            Path wav = files.findFirst().orElseThrow();
            assertThat(Files.readAllBytes(wav)).isEqualTo(WAV);
        }
        assertThat(request("/text-to-speech").getBody().readUtf8()).contains("\"text\":\"namaste\"").contains("\"model\":\"bulbul:v2\"");
        assertThat(h.task(2)).contains("Observation: what is the weather in Pune");
        assertThat(request("/speech-to-text").getHeader("Content-Type")).startsWith("multipart/form-data");
    }

    @Test
    void v2_4_filesStayInsideTheScriptsDirectory() throws Exception {
        Path secret = dir.getParent().resolve("secret-" + System.nanoTime() + ".txt");
        Files.writeString(secret, "top secret");
        try {
            Harness h = harness().answers(
                    Harness.call("transcribe", "{\"path\": \"../" + secret.getFileName() + "\"}"),
                    Harness.call("transcribe", "{\"path\": \"" + secret.toAbsolutePath() + "\"}"),
                    Harness.call("transcribe", "{\"path\": \"missing.wav\"}"),
                    Harness.done("done"));
            h.ready("""
                    agent A { model: "m" tools: [transcribe] }
                    workflow Main() { delegate "go" to A -> out }
                    """).executeWorkflow("Main", Map.of());
            assertThat(h.task(1)).contains("outside the script's directory");
            assertThat(h.task(2)).contains("outside the script's directory");
            assertThat(h.task(3)).contains("no audio file at missing.wav");
            assertThat(received).isEmpty();
        } finally {
            Files.deleteIfExists(secret);
        }
        assertThatThrownBy(() -> harness().ready("tool Say { use: speak out: \"/tmp/elsewhere\" } agent A { model: \"m\" tools: [Say] }"))
                .hasMessageContaining("tool Say: out: /tmp/elsewhere is outside the script's directory");
    }

    static final String VOICE_AGENT = """
            agent Concierge {
                model: "m"
                voice { listen: "sarvam/saarika:v2.5"  speak: "sarvam/bulbul:v2"  language: "hi-IN"  voice: "anushka"  out: "replies" }
            }
            workflow Main() {
                delegate "{question}" to Concierge -> answer
                    on_failure { note "failed: {_error}" }
            }
            """;

    @Test
    void v2_5_andV2_6_listenThenSpeakAndReplayRestoresTheAudio() throws Exception {
        Files.createDirectories(dir.resolve("clips"));
        Files.write(dir.resolve("clips/q.wav"), WAV);
        Path journal = dir.resolve("journal.json");
        Harness h = harness().answers(Harness.done("It is sunny in Pune."));
        h.journal = new FileRunJournal(journal);
        HarnessExecutor e = h.ready(VOICE_AGENT);
        e.executeWorkflow("Main", Map.of("question", "clips/q.wav"));

        assertThat(h.task(0)).contains("what is the weather in Pune").doesNotContain("clips/q.wav");
        String audio = String.valueOf(e.getContext().getVariable("answer_audio"));
        assertThat(audio).startsWith("replies/answer-").endsWith(".wav");
        assertThat(Files.readAllBytes(dir.resolve(audio))).isEqualTo(WAV);
        assertThat(request("/text-to-speech").getBody().readUtf8()).contains("\"speaker\":\"anushka\"")
                .contains("\"target_language_code\":\"hi-IN\"").contains("It is sunny in Pune.");
        assertThat(request("/speech-to-text").getBody().readUtf8()).contains("saarika:v2.5");
        assertThat(h.trace).filteredOn(t -> t.type().equals(TraceEvent.VOICE)).hasSize(2);

        int calls = received.size();
        Harness resumed = harness();
        resumed.journal = new FileRunJournal(journal);
        HarnessExecutor again = resumed.ready(VOICE_AGENT);
        again.executeWorkflow("Main", Map.of("question", "clips/q.wav"));
        assertThat(again.getContext().getVariable("answer_audio")).isEqualTo(audio);
        assertThat(received).hasSize(calls);
        assertThat(resumed.requests).isEmpty();
    }

    @Test
    void aTextTaskIsNotTranscribed() {
        Harness h = harness().answers(Harness.done("Hello!"));
        h.ready(VOICE_AGENT.replace("listen: \"sarvam/saarika:v2.5\"  ", "")).executeWorkflow("Main", Map.of("question", "say hello"));
        assertThat(h.task(0)).contains("say hello");
        assertThat(received).extracting(RecordedRequest::getPath).containsExactly("/text-to-speech");
    }

    @Test
    void v2_7_aSpeechFailureFailsTheStep() {
        ttsFails = true;
        Harness h = harness().answers(Harness.done("answer"));
        HarnessExecutor e = h.ready(VOICE_AGENT);
        e.executeWorkflow("Main", Map.of("question", "hello"));
        assertThat(e.getContext().getAll()).doesNotContainKeys("answer", "answer_audio");
        assertThat(h.requests).hasSize(1); // not retried
    }

    @Test
    void v2_7_voiceSettingsAreChecked() {
        assertThatThrownBy(() -> harness().ready("agent A { model: \"m\" voice { speak: \"elevenlabs/x\" tone: \"warm\" } }"))
                .isInstanceOfSatisfying(LoomLoadException.class, e -> assertThat(e.getMessage())
                        .contains("voice speak: elevenlabs/x is not supported; use sarvam/<model>")
                        .contains("unknown voice setting tone"));
        assertThatThrownBy(() -> new Harness(dir).ready("agent A { model: \"m\" voice { speak: \"sarvam/bulbul:v2\" } }"))
                .hasMessageContaining("voice needs SARVAM_API_KEY");
        assertThatThrownBy(() -> harness().ready("agent A { model: \"m\" voice { language: \"hi-IN\" } }"))
                .hasMessageContaining("voice needs listen:");
        assertThatThrownBy(() -> harness().ready("agent A { model: \"m\" voice { speak: \"sarvam/bulbul:v2\" out: \"../x\" } }"))
                .hasMessageContaining("voice out: ../x is outside the script's directory");
    }
}
