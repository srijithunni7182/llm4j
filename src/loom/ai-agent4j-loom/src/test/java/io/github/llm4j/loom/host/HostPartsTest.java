package io.github.llm4j.loom.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.LoomScript;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The small parts of the host on their own: the durability guard, the answer books and the run request. V8.10, V8.28. */
class HostPartsTest {

    @TempDir
    Path dir;

    private static LoomScript script(String body) {
        return HostEnv.parse(HostEnv.AGENTS + body);
    }

    @Tag("SB-V8.28")
    @Test
    void theGuardNamesEveryConstructThatWaitsAndEveryOneThatNeedsAStore() {
        LoomScript plain = script("workflow Main() { delegate \"x\" to Writer -> y }");
        DurabilityGuard.check(plain, false, false);
        assertThat(DurabilityGuard.suspending(plain)).isEmpty();

        LoomScript sleeps = script("workflow Main() { sleep 1h }");
        assertThat(DurabilityGuard.suspending(sleeps)).singleElement().asString().startsWith("sleep");
        assertThatThrownBy(() -> DurabilityGuard.check(sleeps, false, false)).hasMessageContaining("sleep");
        DurabilityGuard.check(sleeps, false, true);
        DurabilityGuard.check(sleeps, true, false);

        LoomScript asks = script("workflow Main() { human_prompt \"ok?\" -> a }");
        assertThat(DurabilityGuard.suspending(asks)).singleElement().asString().startsWith("human_prompt");

        LoomScript approves = HostEnv.parse("agent Writer { model: \"m\" system: \"You are Writer.\" approve: [Notify] }\nworkflow Main() { delegate \"x\" to Writer -> y }");
        assertThat(DurabilityGuard.suspending(approves)).singleElement().asString().contains("approve");

        LoomScript limits = script("rate_limits { on_limit: suspend }\nworkflow Main() { delegate \"x\" to Writer -> y }");
        assertThat(DurabilityGuard.suspending(limits)).containsExactly("rate_limits");
        LoomScript waits = script("rate_limits { on_limit: wait }\nworkflow Main() { delegate \"x\" to Writer -> y }");
        assertThat(DurabilityGuard.suspending(waits)).isEmpty();

        LoomScript windowed = script("budget { tokens: 1000 per day }\nworkflow Main() { delegate \"x\" to Writer -> y }");
        assertThat(DurabilityGuard.suspending(windowed)).containsExactly("a windowed budget");
        LoomScript total = script("budget { tokens: 1000 }\nworkflow Main() { delegate \"x\" to Writer -> y }");
        assertThat(DurabilityGuard.suspending(total)).isEmpty();

        LoomScript ladder = script("decision D { proposed by: A choices: yes, no ladder: L ask: someone }\nworkflow Main() { delegate \"x\" to Writer -> y }");
        assertThat(DurabilityGuard.needsStore(ladder)).isNotEmpty();
        assertThatThrownBy(() -> DurabilityGuard.check(ladder, false, true)).hasMessageContaining("earned-autonomy");
    }

    @Tag("SB-V8.10")
    @ParameterizedTest @ValueSource(strings = {"memory", "file", "jdbc"})
    void anAnswerBookAsksOnceAnswersOnceAndKeepsWhatItWasTold(String kind) {
        AnswerBook book = switch (kind) {
            case "memory" -> new AnswerBook.Memory();
            case "file" -> new AnswerBook.Files_(dir);
            default -> {
                javax.sql.DataSource ds = new HostEnv("jdbc", dir).ds;
                yield new AnswerBook.Jdbc(ds);
            }
        };
        Instant t = Instant.parse("2026-10-15T07:00:00Z");
        assertThat(book.find("r", "s")).isEmpty();
        assertThat(book.answer("r", "s", "yes", "asha", t)).isFalse();
        AnswerBook.Question q = book.ask("r", "s", "Go?", List.of("yes", "no"), t);
        assertThat(q.answered()).isFalse();
        assertThat(book.ask("r", "s", "different", List.of(), t).message()).isEqualTo("Go?");
        assertThat(book.find("r", "s")).get().extracting(AnswerBook.Question::choices).isEqualTo(List.of("yes", "no"));
        assertThat(book.answer("r", "s", "yes", "asha", t.plusSeconds(5))).isTrue();
        assertThat(book.answer("r", "s", "no", "bo", t.plusSeconds(6))).isFalse();
        AnswerBook.Question done = book.find("r", "s").orElseThrow();
        assertThat(done.answered()).isTrue();
        assertThat(done.answer()).isEqualTo("yes");
        assertThat(done.by()).isEqualTo("asha");
        AnswerBook.Question open = book.ask("r", "t", "Later?", null, t);
        assertThat(open.choices()).isEmpty();
        assertThat(book.find("r", "t").orElseThrow().answered()).isFalse();
    }

    @Tag("SB-V8.1")
    @Test
    void aRequestNeedsAWorkflowAndDefaultsItsOrigin() {
        assertThatThrownBy(() -> RunRequest.of(" ", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunRequest.of(null, Map.of())).isInstanceOf(IllegalArgumentException.class);
        RunRequest r = RunRequest.of("Main", null);
        assertThat(r.inputs()).isEmpty();
        assertThat(r.origin()).isEqualTo("app");
        assertThat(r.labelled("L").from("web").withRunId("id").label()).isEqualTo("L");
        assertThat(r.from(" ").origin()).isEqualTo("app");
    }
}
