package io.github.llm4j.loom.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R26, R31 of spec 12: the model's side of a generation, recorded and replayed, with no request
 * text and no keys.
 */
class TranscriptTest {

    @TempDir Path dir;

    static LLMClient saying(String... replies) {
        int[] n = {0};
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest r) {
                return LLMResponse.builder()
                        .content(replies[n[0]++])
                        .model("m")
                        .tokenUsage(10, 5, 15)
                        .build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest r) {
                return Stream.of(chat(r));
            }
        };
    }

    static LLMRequest ask(String text) {
        return LLMRequest.builder()
                .model("m")
                .addSystemMessage("system")
                .addUserMessage(text)
                .build();
    }

    @Test
    void whatWasRecordedIsReplayedInOrder() throws Exception {
        Transcript.Recording rec = new Transcript.Recording(saying("one", "two"));
        rec.chat(ask("a"));
        rec.chat(ask("b"));
        Path file = dir.resolve("t.jsonl");
        rec.save(file);
        Transcript.Replay replay = Transcript.Replay.read(file);
        assertThat(replay.chat(ask("a")).getContent()).isEqualTo("one");
        var second = replay.chat(ask("b"));
        assertThat(second.getContent()).isEqualTo("two");
        assertThat(second.getTokenUsage().getTotalTokens()).isEqualTo(15);
        assertThat(replay.used()).isEqualTo(2);
    }

    @Test
    void aTranscriptHoldsNoRequestTextOnlyItsFingerprint() throws Exception {
        Transcript.Recording rec = new Transcript.Recording(saying("ok"));
        rec.chat(ask("the secret customer description"));
        Path file = dir.resolve("t.jsonl");
        rec.save(file);
        assertThat(Files.readString(file))
                .doesNotContain("customer description")
                .contains(Transcript.fingerprint(ask("the secret customer description")));
    }

    @Test
    void aReplyShapedLikeAKeyIsNotSaved() {
        Transcript.Recording rec =
                new Transcript.Recording(saying("use sk-abcdefghijklmnopqrstuvwxyz012345 now"));
        rec.chat(ask("a"));
        Path file = dir.resolve("t.jsonl");
        assertThatThrownBy(() -> rec.save(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("shaped like a key");
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void replayStopsClearlyWhenWhatIsAskedHasChangedOrRunsOut() throws Exception {
        Transcript.Recording rec = new Transcript.Recording(saying("one"));
        rec.chat(ask("a"));
        Path file = dir.resolve("t.jsonl");
        rec.save(file);
        Transcript.Replay changed = Transcript.Replay.read(file);
        assertThatThrownBy(() -> changed.chat(ask("different")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Call 1 is not what the transcript recorded");
        Transcript.Replay exhausted = Transcript.Replay.read(file);
        exhausted.chat(ask("a"));
        assertThatThrownBy(() -> exhausted.chat(ask("a"))).hasMessageContaining("holds 1 calls");
    }

    @Test
    void theSameRequestHasTheSameFingerprintAndADifferentOneDoesNot() {
        assertThat(Transcript.fingerprint(ask("a")))
                .isEqualTo(Transcript.fingerprint(ask("a")))
                .isNotEqualTo(Transcript.fingerprint(ask("b")));
    }

    @Test
    void byOrderTheNthReplyAnswersTheNthCallWhateverIsAsked() throws Exception {
        Transcript.Recording rec = new Transcript.Recording(saying("one", "two"));
        rec.chat(ask("a"));
        rec.chat(ask("b"));
        Path file = dir.resolve("t.jsonl");
        rec.save(file);
        Transcript.Replay replay = Transcript.Replay.read(file).byOrder(true);
        assertThat(replay.chat(ask("changed prompt")).getContent()).isEqualTo("one");
        assertThat(replay.chat(ask("another")).getContent()).isEqualTo("two");
        assertThatThrownBy(() -> replay.chat(ask("a"))).hasMessageContaining("holds 2 calls");
    }
}
