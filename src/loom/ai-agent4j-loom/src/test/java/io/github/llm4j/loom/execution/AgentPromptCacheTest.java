package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.PromptCache;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code cache: false} on an agent (docs/specs/31-multimodal-input, R5 and D6): an agent's loop lets
 * the provider cache what it resends unless the script says not to.
 */
class AgentPromptCacheTest {

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private static String script(String setting) {
        return """
               agent Writer {
                   model: "test"
                   system: "You are Writer."
                   tools: [MockTool]
                   %s
               }

               workflow Main(topic) {
                   delegate "Write about {topic}" to Writer -> text
                   return text
               }
               """
                .formatted(setting);
    }

    /**
     * Remembers each request's cache; says it can call tools, so the agent's loop is the native
     * one.
     */
    private static final class Recording implements LLMClient {
        final List<PromptCache> caches = new ArrayList<>();

        @Override
        public LLMResponse chat(LLMRequest request) {
            caches.add(request.getPromptCache());
            return LLMResponse.builder().content("done").model("test").build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            return Stream.of(chat(request));
        }

        @Override
        public boolean supportsToolCalling() {
            return true;
        }
    }

    private static Recording run(String setting) {
        Recording model = new Recording();
        ToolRegistry tools = new ToolRegistry();
        tools.register("MockTool", new MockTool());
        HarnessExecutor executor =
                new HarnessExecutor(parse(script(setting)), tools, name -> model);
        executor.initialize();
        executor.executeWorkflow("Main", Map.of("topic", "the sea"));
        return model;
    }

    @Test
    void anAgentCachesByDefault() {
        assertThat(parse(script("")).getAgents().get(0).isPromptCaching()).isTrue();
    }

    @Test
    void cacheFalseTurnsItOff() {
        assertThat(parse(script("cache: false")).getAgents().get(0).isPromptCaching()).isFalse();
    }

    @Test
    void cacheTrueIsTheSameAsTheDefault() {
        assertThat(parse(script("cache: true")).getAgents().get(0).isPromptCaching()).isTrue();
    }

    @Test
    void anythingElseIsAParseErrorThatNamesTheChoices() {
        assertThatThrownBy(() -> parse(script("cache: maybe")))
                .hasMessageContaining("cache must be true or false");
    }

    @Test
    void theAgentsCallsCarryTheCacheUnlessItIsOff() {
        assertThat(run("").caches).isNotEmpty().allSatisfy(c -> assertThat(c).isNotNull());
        assertThat(run("cache: false").caches).isNotEmpty().containsOnlyNulls();
    }

    @Test
    void cacheIsAListedAgentSetting() {
        assertThatThrownBy(() -> parse(script("cash: false"))).hasMessageContaining("cache");
    }
}
