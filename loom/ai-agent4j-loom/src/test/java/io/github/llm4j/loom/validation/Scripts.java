package io.github.llm4j.loom.validation;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Builds executors over scripted models that record every request (system prompt, model, task). */
final class Scripts {

    record Call(String model, String system, String task) { }

    final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    Set<String> failingModels = Set.of();
    List<String> answers = List.of();

    static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    LLMClient client(String model) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String system = request.getMessages().get(0).getContent();
                String task = request.getMessages().get(request.getMessages().size() - 1).getContent();
                calls.add(new Call(model, system, task));
                if (failingModels.contains(model)) throw new IllegalStateException(model + " is down");
                int n = calls.size();
                String content = n <= answers.size() ? answers.get(n - 1)
                        : "```json\n{\"thought\": \"t\", \"final_answer\": \"answer from " + model + "\"}\n```";
                return LLMResponse.builder().content(content).model(model).tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
    }

    HarnessExecutor executor(String source, Consumer<HarnessExecutor> configure) {
        return executor(source, new ToolRegistry(), configure);
    }

    HarnessExecutor executor(String source, ToolRegistry tools, Consumer<HarnessExecutor> configure) {
        HarnessExecutor e = new HarnessExecutor(parse(source), tools, this::client);
        if (configure != null) configure.accept(e);
        return e;
    }

    HarnessExecutor ready(String source) {
        HarnessExecutor e = executor(source, null);
        e.initialize();
        return e;
    }

    static Map<String, String> env(String... kv) {
        java.util.HashMap<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
