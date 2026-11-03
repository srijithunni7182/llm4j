package io.github.llm4j.loom.discipline;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.security.AuditReport;
import io.github.llm4j.loom.security.SecurityAudit;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 24 section 0: reproduces what the code does today (E1, E3, E6), so the behaviour the phase
 * keeps by default is pinned and the behaviour it changes is shown first.
 */
class Phase24ReproductionTest {

    private static final String SHARED_CANARY = "SHARED-CANARY-77";
    private static final String ANSWER_CANARY = "ANSWER-CANARY-42";

    private static final String SCRIPT =
            "shared_prompt: \"" + SHARED_CANARY + "\"\n"
                    + "agent First { model: \"test\" system: \"You are First.\" }\n"
                    + "agent Second { model: \"test\" system: \"You are Second.\" }\n"
                    + "workflow Main() {\n"
                    + "  delegate \"do the first thing\" to First -> a\n"
                    + "  delegate \"do the second thing\" to Second -> b\n"
                    + "}\n";

    private static List<LLMRequest> run() {
        List<LLMRequest> requests = Collections.synchronizedList(new ArrayList<>());
        LLMClientFactory factory =
                model ->
                        new LLMClient() {
                            @Override
                            public LLMResponse chat(LLMRequest request) {
                                requests.add(request);
                                return LLMResponse.builder()
                                        .content(ANSWER_CANARY)
                                        .model(model)
                                        .build();
                            }

                            @Override
                            public Stream<LLMResponse> chatStream(LLMRequest request) {
                                return Stream.empty();
                            }
                        };
        LoomScript script = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), factory);
        executor.initialize();
        executor.executeWorkflow("Main", Map.of());
        return requests;
    }

    private static String text(LLMRequest r) {
        StringBuilder sb = new StringBuilder();
        r.getMessages().forEach(m -> sb.append(m.getContent()).append('\n'));
        return sb.toString();
    }

    @Test
    void p1TheSecondAgentReceivesTheFirstAgentsAnswerInTheTranscript() {
        List<LLMRequest> requests = run();
        assertThat(requests).hasSize(2);
        assertThat(text(requests.get(1))).contains("Conversation History").contains(ANSWER_CANARY);
        assertThat(text(requests.get(0))).doesNotContain("Conversation History");
    }

    @Test
    void p3EveryAgentReceivesTheSharedPrompt() {
        List<LLMRequest> requests = run();
        assertThat(text(requests.get(0))).contains(SHARED_CANARY);
        assertThat(text(requests.get(1))).contains(SHARED_CANARY);
    }

    @Test
    void p6TheAuditSaysNothingAboutTextTravellingThroughTheTranscript() {
        String source =
                "tool Files { use: file  root: \"data\"  mode: read }\n"
                        + "tool Mail  { use: email  host: \"smtp.example.com\"  from: \"a@example.com\"  to: \"me@example.com\" }\n"
                        + "agent Researcher { model: \"m\" system: \"s\" tools: [web_search] }\n"
                        + "agent Sender { model: \"m\" system: \"s\" tools: [Files, Mail] approve: [Mail] }\n"
                        + "workflow Main(q) {\n"
                        + "  delegate \"{q}\" to Researcher -> r\n"
                        + "  delegate \"send it\" to Sender -> s\n"
                        + "}\n";
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        AuditReport report = SecurityAudit.audit(script, "taint.loom");
        assertThat(report.findings()).noneMatch(f -> f.rule().equals("LA01"));
        assertThat(report.findings()).noneMatch(f -> f.rule().equals("LA21"));
    }
}
