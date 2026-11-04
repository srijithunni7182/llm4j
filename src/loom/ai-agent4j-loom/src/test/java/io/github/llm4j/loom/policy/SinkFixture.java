package io.github.llm4j.loom.policy;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Runs a script with scripted models, a policy file and a canary in every model answer. */
final class SinkFixture {

    static final String CANARY = "CANARY-7f3a";
    static final String EXTRACTED =
            "{\"summary\":\"" + CANARY + " summary\",\"points\":[\"" + CANARY + " point\"],\"amount\":12,\"kind\":\"refund\"}";
    static final String CLEAN = "{\"no_promise\":{\"violated\":\"no\",\"reason\":\"fine\"},\"no_advice\":{\"violated\":\"no\",\"reason\":\"fine\"}}";
    static final String BROKEN = "{\"no_promise\":{\"violated\":\"yes\",\"reason\":\"promises\"},\"no_advice\":{\"violated\":\"no\",\"reason\":\"fine\"}}";

    static final String HEAD =
            """
            evaluator Compliance { model: "judge" }
            policy HouseRules: "house-rules" checked by Compliance
            agent Writer { model: "writer" system: "You write replies." }
            agent Editor { model: "editor" system: "You edit replies." }
            extractor Claim {
                model: "extractor"
                fields { summary: string  points: list<string>  amount: number  kind: enum["refund", "other"] }
            }
            """;

    record Outcome(
            Throwable error,
            Map<String, Object> vars,
            List<TraceEvent> events,
            RunJournal journal,
            List<LLMRequest> requests) {
        String message() {
            return error == null ? "" : String.valueOf(error.getMessage());
        }

        boolean blocked() {
            return error != null && events.stream().anyMatch(e -> e.type().equals(TraceEvent.SINK_BLOCKED));
        }

        Object result() {
            return vars.get("result");
        }
    }

    static String who(LLMRequest r) {
        String all = r.getMessages().stream().map(m -> m.getContent()).reduce("", (a, b) -> a + "\n" + b);
        if (all.contains("You are a policy evaluator.")) return "judge";
        if (all.contains("You edit replies.")) return "editor";
        if (all.contains("You are a field extractor.")) return "extractor";
        return "writer";
    }

    static Outcome run(Path dir, String script, String workflow, Map<String, String> inputs) throws Exception {
        return run(dir, script, workflow, inputs, null, null, null);
    }

    static Outcome run(
            Path dir,
            String script,
            String workflow,
            Map<String, String> inputs,
            HumanInterface human,
            RunJournal journal,
            io.github.llm4j.agent.task.TaskRegistry tasks)
            throws Exception {
        Files.createDirectories(dir.resolve("policies"));
        Files.writeString(
                dir.resolve("policies/house-rules.md"),
                "## no_promise\nThe reply promises a refund.\n");
        Files.writeString(dir.resolve("policies/legal.md"), "## no_advice\nThe reply gives legal advice.\n");
        List<LLMRequest> all = Collections.synchronizedList(new ArrayList<>());
        io.github.llm4j.LLMClient client =
                new io.github.llm4j.LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        all.add(request);
                        String who = who(request);
                        String text = request.getMessages().get(request.getMessages().size() - 1).getContent();
                        String reply =
                                switch (who) {
                                    case "judge" -> text.contains("refund") ? BROKEN : CLEAN;
                                    case "editor" -> "Thanks, we will look into it.";
                                    case "extractor" -> EXTRACTED;
                                    default -> text.contains("bad")
                                            ? CANARY + " we will refund you"
                                            : CANARY + " we are looking into it";
                                };
                        return LLMResponse.builder().content(reply).model("t").build();
                    }

                    @Override
                    public java.util.stream.Stream<LLMResponse> chatStream(LLMRequest request) {
                        return java.util.stream.Stream.empty();
                    }
                };
        LoomScript parsed = new LoomParser(new Lexer(script).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(parsed, new ToolRegistry(), model -> client);
        executor.setBaseDir(dir);
        executor.setStaticSinkChecks(false); // these scripts test the runtime guard on its own
        if (human != null) executor.setHumanInterface(human);
        if (tasks != null) executor.setTaskRegistry(tasks);
        if (journal != null) executor.setJournal(journal);
        List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        executor.addTraceListener(events::add);
        executor.initialize();
        Throwable error = null;
        try {
            executor.executeWorkflow(workflow, inputs);
        } catch (Throwable t) {
            error = t;
        }
        return new Outcome(error, executor.getContext().getAll(), events, executor.getJournal(), all);
    }
}
