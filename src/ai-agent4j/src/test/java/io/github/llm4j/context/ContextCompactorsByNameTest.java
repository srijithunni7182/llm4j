package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.model.Message;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Strategies are found by name through ServiceLoader, the built-ins by the same path as a
 * developer's own (R3, A3).
 */
class ContextCompactorsByNameTest {

    @Test
    void theBuiltInsAreFoundByName() {
        assertThat(ContextCompactors.byName("none", Map.of()).name()).isEqualTo("none");
        assertThat(ContextCompactors.byName("drop_oldest", Map.of()).name())
                .isEqualTo("drop_oldest");
        assertThat(ContextCompactors.byName("trim_tool_results", Map.of()).name())
                .isEqualTo("trim_tool_results");
        assertThat(ContextCompactors.names())
                .contains("none", "drop_oldest", "trim_tool_results", "chain");
    }

    @Test
    void optionsAreApplied() {
        List<Message> in =
                new Convo()
                        .system("s")
                        .task("t")
                        .step("a", 9_000)
                        .step("b", 9_000)
                        .step("c", 9_000)
                        .build();
        ContextCompactor small =
                ContextCompactors.byName("trim_tool_results", Map.of("max_chars", "200"));
        CompactionResult r = small.compact(in, CompactionRequest.builder(1).keepRecent(1).build());
        assertThat(r.messages().get(3).getContent().length()).isLessThan(400);
    }

    @Test
    void aChainIsBuiltFromNames() {
        ContextCompactor c =
                ContextCompactors.byName(
                        "chain", Map.of("strategies", "trim_tool_results, drop_oldest"));
        assertThat(c.name()).isEqualTo("chain(trim_tool_results, drop_oldest)");
        assertThatThrownBy(() -> ContextCompactors.byName("chain", Map.of()))
                .hasMessageContaining("strategies");
        assertThatThrownBy(
                        () ->
                                ContextCompactors.byName(
                                        "chain", Map.of("strategies", "drop_oldest, nope")))
                .hasMessageContaining("nope");
    }

    @Test
    void aStrategyFromAnotherJarIsFoundThroughTheServiceLoader() {
        ContextCompactor c = ContextCompactors.byName("test_keep_task_only", Map.of());
        List<Message> in = new Convo().system("s").task("t").step("a", 3_000).build();
        assertThat(c.compact(in, CompactionRequest.of(1)).messages()).hasSize(2);
        assertThat(ContextCompactors.names()).contains("test_keep_task_only");
    }

    @Test
    void anUnknownNameSuggestsTheNearestOne() {
        assertThatThrownBy(() -> ContextCompactors.byName("drop_oldes", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("drop_oldes")
                .hasMessageContaining("did you mean 'drop_oldest'");
        assertThatThrownBy(() -> ContextCompactors.byName("zzzzzzzzzzzz", Map.of()))
                .hasMessageContaining("known strategies")
                .hasMessageContaining("none");
    }

    @Test
    void badOptionsAreReportedByName() {
        assertThatThrownBy(
                        () ->
                                ContextCompactors.byName(
                                        "trim_tool_results", Map.of("max_chars", "lots")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_chars");
        assertThatThrownBy(() -> ContextCompactors.byName("drop_oldest", Map.of("colour", "red")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("colour")
                .hasMessageContaining("takes no options");
        assertThatThrownBy(
                        () ->
                                ContextCompactors.byName(
                                        "trim_tool_results", Map.of("colour", "red")))
                .hasMessageContaining("colour")
                .hasMessageContaining("max_chars");
    }

    @Test
    void byNameWithoutAModelClientCannotBuildOneThatNeedsIt() {
        // summarize arrives in sub-phase 1d; the provider context is where a client is handed over
        assertThat(ProviderContext.none().llmClient()).isEmpty();
        io.github.llm4j.LLMClient client =
                org.mockito.Mockito.mock(io.github.llm4j.LLMClient.class);
        assertThat(ProviderContext.of(client).llmClient()).containsSame(client);
    }

    @Test
    void summarizeIsFoundByNameAndNeedsAClient() {
        assertThat(ContextCompactors.names()).contains("summarize");
        assertThatThrownBy(() -> ContextCompactors.byName("summarize", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a model client");
        ProviderContext with = ProviderContext.of(SummariserModel.saying("notes"));
        assertThat(ContextCompactors.byName("summarize", Map.of(), with).name())
                .isEqualTo("summarize");
        SummariserModel model = SummariserModel.saying("notes");
        ContextCompactors.byName(
                        "summarize", Map.of("max_summary_tokens", "123"), ProviderContext.of(model))
                .compact(
                        new Convo()
                                .system("s")
                                .task("t")
                                .step("a", 4_000)
                                .step("b", 4_000)
                                .step("c", 4_000)
                                .build(),
                        CompactionRequest.builder(300).keepRecent(2).build());
        assertThat(model.requests.get(0).getMaxTokens()).isEqualTo(123);
        assertThatThrownBy(
                        () ->
                                ContextCompactors.byName(
                                        "summarize", Map.of("max_summary_tokens", "lots"), with))
                .hasMessageContaining("max_summary_tokens");
        assertThatThrownBy(
                        () -> ContextCompactors.byName("summarize", Map.of("colour", "red"), with))
                .hasMessageContaining("colour")
                .hasMessageContaining("max_summary_tokens");
    }

    @Test
    void aChainCanIncludeSummarizeWhenAClientIsGiven() {
        ContextCompactor c =
                ContextCompactors.byName(
                        "chain",
                        Map.of("strategies", "trim_tool_results, summarize, drop_oldest"),
                        ProviderContext.of(SummariserModel.saying("notes")));
        assertThat(c.name()).isEqualTo("chain(trim_tool_results, summarize, drop_oldest)");
    }
}
