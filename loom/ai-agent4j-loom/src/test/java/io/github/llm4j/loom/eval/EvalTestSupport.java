package io.github.llm4j.loom.eval;

import io.github.llm4j.LLMClient;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/** Models that answer from a function of what they are asked, and a record of what they were asked. */
final class EvalTestSupport {

    private EvalTestSupport() {}

    static String finalAnswer(String answer) {
        return "```json\n{\"thought\": \"t\", \"final_answer\": \"" + answer.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}\n```";
    }

    static String useTool(String tool, String queryJson) {
        return "```json\n{\"thought\": \"look\", \"action\": \"" + tool + "\", \"action_input\": " + queryJson + "}\n```";
    }

    /** Answers by (system prompt, number of the call so far). */
    static final class Models implements LLMClientFactory {
        final List<String> systems = Collections.synchronizedList(new ArrayList<>());
        final List<String> models = Collections.synchronizedList(new ArrayList<>());
        private final BiFunction<String, Integer, String> answer;
        private int calls;

        Models(BiFunction<String, Integer, String> answer) {
            this.answer = answer;
        }

        int calls() {
            return calls;
        }

        @Override
        public LLMClient createClient(String model) {
            return new LLMClient() {
                @Override
                public LLMResponse chat(LLMRequest request) {
                    String system = request.getMessages().get(0).getContent();
                    systems.add(system);
                    models.add(model);
                    int n;
                    synchronized (Models.this) {
                        n = calls++;
                    }
                    return LLMResponse.builder().content(answer.apply(system, n)).model(model).tokenUsage(10, 5, 15).build();
                }

                @Override
                public Stream<LLMResponse> chatStream(LLMRequest request) {
                    return Stream.of(chat(request));
                }
            };
        }
    }

    static EvalScenario scenario(String name, String input) {
        return new EvalScenario(name, input, null, null, null, null, null, null, null, null);
    }

    static EvalScenario with(EvalScenario s, String contains, List<String> tools, List<String> rubric, List<String> expect) {
        return new EvalScenario(s.name(), s.input(), contains, null, tools, null, null, s.id(), null, null, rubric, expect);
    }

    static HarnessExecutor executor(String source, LLMClientFactory models, ToolRegistry tools) {
        return executor(source, models, tools, e -> { });
    }

    static HarnessExecutor executor(String source, LLMClientFactory models, ToolRegistry tools, java.util.function.Consumer<HarnessExecutor> beforeInitialize) {
        HarnessExecutor e = new HarnessExecutor(new LoomParser(new Lexer(source).tokenize()).parseScript(), tools, models);
        e.setHumanInterface(m -> "yes");
        e.setEnvLookup(name -> "x");
        beforeInitialize.accept(e);
        e.initialize();
        return e;
    }
}
