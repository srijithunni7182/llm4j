package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoadException;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.runtime.FileRunJournal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan V1: agent memory. */
class MemoryTest {

    @TempDir
    Path dir;

    static final String CHAT = """
            agent Assistant {
                model: "m"
                memory {
                    conversation: "chats"
                    session: "{user}"
                    limit: 20
                }
            }
            workflow Main() { delegate "{message}" to Assistant -> reply }
            """;

    @Test
    void v1_1_theMemoryBlockParsesWithOrWithoutAColon() {
        new Harness(dir).ready(CHAT);
        new Harness(dir).ready(CHAT.replace("memory {", "memory: {"));
        new Harness(dir).ready("""
                agent A {
                    model: "m"
                    memory { facts: memory  embedding: "test/hash"  recall: 3  min_similarity: 0.2 }
                }
                """);
    }

    @Test
    void v1_1_badSettingsAreLoadErrors() {
        assertThatThrownBy(() -> new Harness(dir).ready("""
                agent A {
                    model: "m"
                    memory {
                        conversation: memory
                        limit: 0
                        recall: -1
                        min_similarity: 2
                        colour: "blue"
                        session: env.USER
                    }
                }
                """)).isInstanceOfSatisfying(LoomLoadException.class, e -> assertThat(e.getMessage())
                .contains("line 5: agent A: limit must be a positive whole number")
                .contains("line 6: agent A: recall must be a positive whole number")
                .contains("line 7: agent A: memory min_similarity must be between 0 and 1")
                .contains("line 8: agent A: unknown memory setting colour")
                .contains("line 9: agent A: memory session is written in the script"));
        assertThatThrownBy(() -> new Harness(dir).ready("agent A { model: \"m\" memory { limit: 5 } }"))
                .hasMessageContaining("memory needs conversation:");
        assertThatThrownBy(() -> new Harness(dir).ready("agent A { model: \"m\" memory { type: \"file\" path: \"x\" } }"))
                .hasMessageContaining("memory type: is no longer used").hasMessageContaining("memory path: is no longer used");
    }

    @Test
    void v1_2_conversationsPersistAcrossExecutors() {
        Harness first = new Harness(dir).answers(Harness.done("Nice to meet you, Asha."));
        first.ready(CHAT).executeWorkflow("Main", Map.of("user", "u1", "message", "My name is Asha"));

        Harness second = new Harness(dir);
        HarnessExecutor e = second.ready(CHAT);
        e.executeWorkflow("Main", Map.of("user", "u1", "message", "What is my name?"));
        assertThat(second.task(0))
                .contains("Previous conversation (u1):")
                .contains("user: My name is Asha")
                .contains("assistant: Nice to meet you, Asha.")
                .contains("What is my name?");
        assertThat(dir.resolve("chats")).isDirectory();
    }

    @Test
    void v1_3_sessionsAreSeparate() {
        new Harness(dir).ready(CHAT).executeWorkflow("Main", Map.of("user", "u1", "message", "My name is Asha"));
        Harness other = new Harness(dir);
        other.ready(CHAT).executeWorkflow("Main", Map.of("user", "u2", "message", "What is my name?"));
        assertThat(other.task(0)).doesNotContain("Asha").doesNotContain("Previous conversation");
        // a session name that sanitises to the same text still gets its own file
        new Harness(dir).ready(CHAT).executeWorkflow("Main", Map.of("user", "u/1", "message", "hi"));
        assertThat(dir.resolve("chats").toFile().list()).hasSize(3);
    }

    @Test
    void v1_4_onlyTheLastLimitMessagesAreShown() {
        String script = CHAT.replace("limit: 20", "limit: 2");
        for (String m : new String[] {"first", "second", "third"}) {
            new Harness(dir).answers(Harness.done("re " + m)).ready(script)
                    .executeWorkflow("Main", Map.of("user", "u1", "message", m));
        }
        Harness h = new Harness(dir);
        h.ready(script).executeWorkflow("Main", Map.of("user", "u1", "message", "fourth"));
        assertThat(h.task(0)).contains("user: third").contains("assistant: re third").doesNotContain("second");
    }

    @Test
    void v1_5_factsAreSavedAndRecalledPerSession() {
        String script = """
                agent Assistant {
                    model: "m"
                    memory { facts: "memory/facts.json"  embedding: "test/hash"  session: "{user}"  min_similarity: 0.1 }
                }
                workflow Main() { delegate "{message}" to Assistant -> reply }
                """;
        Harness first = new Harness(dir).answers(
                Harness.call("save_memory_fact", "{\"fact\": \"The user drinks green tea every morning\"}"),
                Harness.done("Noted."));
        first.ready(script).executeWorkflow("Main", Map.of("user", "u1", "message", "I drink green tea every morning"));
        assertThat(first.task(1)).contains("Successfully saved fact");
        assertThat(dir.resolve("memory/facts.json")).exists();

        Harness second = new Harness(dir);
        second.ready(script).executeWorkflow("Main", Map.of("user", "u1", "message", "Which tea should I buy for the morning?"));
        assertThat(second.task(0)).contains("Relevant context from user's long-term memory:")
                .contains("- The user drinks green tea every morning");

        Harness stranger = new Harness(dir);
        stranger.ready(script).executeWorkflow("Main", Map.of("user", "u2", "message", "Which tea should I buy for the morning?"));
        assertThat(stranger.task(0)).doesNotContain("green tea every morning");
    }

    @Test
    void v1_6_replayedStepsDontTouchMemory() throws Exception {
        Path journal = dir.resolve("journal.json");
        Harness first = new Harness(dir);
        first.journal = new FileRunJournal(journal);
        first.ready(CHAT).executeWorkflow("Main", Map.of("user", "u1", "message", "hello"));
        Path chats = dir.resolve("chats");
        String before;
        try (var files = Files.list(chats)) {
            Path file = files.findFirst().orElseThrow();
            before = Files.readString(file);
            Harness resumed = new Harness(dir);
            resumed.journal = new FileRunJournal(journal);
            resumed.ready(CHAT).executeWorkflow("Main", Map.of("user", "u1", "message", "hello"));
            assertThat(resumed.requests).isEmpty();
            assertThat(Files.readString(file)).isEqualTo(before);
            assertThat(resumed.trace).extracting(TraceEvent::type).contains(TraceEvent.DELEGATE_REPLAYED)
                    .doesNotContain(TraceEvent.MEMORY);
        }
    }

    @Test
    void v1_7_recallIsAuditedAndTraced() {
        Harness h = new Harness(dir);
        h.ready(CHAT).executeWorkflow("Main", Map.of("user", "u1", "message", "hi"));
        assertThat(h.audit).anySatisfy(a -> assertThat(a).startsWith("memory_recalled").contains("session=u1").contains("agent=Assistant"));
        assertThat(h.trace).anySatisfy(t -> {
            assertThat(t.type()).isEqualTo(TraceEvent.MEMORY);
            assertThat(t.agent()).isEqualTo("Assistant");
        });
    }

    @Test
    void v1_8_factsNeedAWorkingEmbedding() {
        assertThatThrownBy(() -> new Harness(dir).ready("agent A { model: \"m\" memory { facts: memory } }"))
                .hasMessageContaining("memory facts need embedding:");
        Harness h = new Harness(dir);
        assertThatThrownBy(() -> h.executor("agent A { model: \"m\" memory { facts: memory embedding: \"nope/x\" } }",
                e -> e.setEmbeddingFactory(new io.github.llm4j.loom.knowledge.DefaultEmbeddingFactory(n -> null))).initialize())
                .isInstanceOf(LoomLoadException.class).hasMessageContaining("line 1: agent A");
    }

    @Test
    void theDefaultSessionIsTheAgentAndAnUnresolvedOneFallsBackToIt() {
        String script = CHAT.replace("    session: \"{user}\"\n", "");
        Harness h = new Harness(dir);
        h.ready(script).executeWorkflow("Main", Map.of("message", "hi"));
        assertThat(h.audit).anySatisfy(a -> assertThat(a).contains("session=Assistant"));
    }

    @Test
    void aCorruptFactsFileFailsTheLoad() throws Exception {
        Files.writeString(dir.resolve("facts.json"), "{broken");
        assertThatThrownBy(() -> new Harness(dir).ready("agent A { model: \"m\" memory { facts: \"facts.json\" embedding: \"test/hash\" } }"))
                .isInstanceOf(LoomLoadException.class).hasMessageContaining("memory can't be opened");
    }
}
