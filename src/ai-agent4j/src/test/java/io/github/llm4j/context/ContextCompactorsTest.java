package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.model.Message;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextCompactorsTest {

    @Test
    void aLambdaIsACompactor() {
        ContextCompactor mine = (messages, request) -> CompactionResult.of("mine", messages, messages.subList(0, 2), request);
        List<Message> in = new Convo().system("s").task("t").step("a", 3_000).build();
        CompactionResult r = mine.compact(in, CompactionRequest.of(10));
        assertThat(r.messages()).hasSize(2);
        assertThat(r.strategy()).isEqualTo("mine");
    }

    @Test
    void aLambdaGetsAGenericNameAndAClassKeepsItsOwn() {
        ContextCompactor lambda = (m, r) -> CompactionResult.unchanged("x", m, r, CompactionResult.Outcome.SKIPPED, "");
        assertThat(lambda.name()).isEqualTo("custom");
        assertThat(new Mine().name()).isEqualTo("Mine");
        assertThat(new Named().name()).isEqualTo("my-name");
    }

    @Test
    void noneReturnsTheInputAndSaysItDidNothing() {
        List<Message> in = new Convo().system("s").task("t").step("a", 3_000).build();
        CompactionResult r = ContextCompactors.none().compact(in, CompactionRequest.of(1));
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.SKIPPED);
        assertThat(r.messages()).isEqualTo(in);
        assertThat(r.strategy()).isEqualTo("none");
    }

    @Test
    void theBuiltInsNameThemselvesAsTheyAreFoundByName() {
        assertThat(ContextCompactors.none().name()).isEqualTo("none");
        assertThat(ContextCompactors.dropOldest().name()).isEqualTo("drop_oldest");
        assertThat(ContextCompactors.trimToolResults(1_000).name()).isEqualTo("trim_tool_results");
        assertThat(ContextCompactors.chain(ContextCompactors.dropOldest()).name()).isEqualTo("chain(drop_oldest)");
    }

    @Test
    void theInputIsNeverModified() {
        List<Message> in = new ArrayList<>(new Convo().system("s").task("t").step("a", 3_000).step("b", 3_000).step("c", 3_000).build());
        List<Message> copy = List.copyOf(in);
        ContextCompactors.dropOldest().compact(in, CompactionRequest.of(10));
        assertThat(in).isEqualTo(copy);
    }

    static final class Mine implements ContextCompactor {
        @Override
        public CompactionResult compact(List<Message> messages, CompactionRequest request) {
            return CompactionResult.of(this.name(), messages, messages, request);
        }
    }

    static final class Named implements ContextCompactor {
        @Override
        public CompactionResult compact(List<Message> messages, CompactionRequest request) {
            return CompactionResult.of(name(), messages, messages, request);
        }

        @Override
        public String name() {
            return "my-name";
        }
    }
}
