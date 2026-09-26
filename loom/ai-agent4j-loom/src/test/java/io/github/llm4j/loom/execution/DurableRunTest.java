package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.JdbcRunJournal;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Durable runs: every step's result is journaled, a human prompt suspends without holding a thread,
 * and running the workflow again with the same journal replays what happened and carries on.
 */
class DurableRunTest {

    @TempDir
    Path dir;

    private static final String SCRIPT = """
            agent Writer {
                model: "test"
                system: "You are Writer."
                output_schema: { draft: string }
            }

            agent Editor {
                model: "test"
                system: "You are Editor."
            }

            workflow Main(topic) {
                delegate "Draft about {topic}" to Writer -> draft
                human_prompt "Which angle for {draft.draft}?" -> choice
                delegate "Edit {draft.draft} with angle {choice}" to Editor -> final
            }
            """;

    /** A scripted model that records every call. */
    static LLMClientFactory model(List<String> calls, AtomicInteger failEditorTimes) {
        return name -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String system = request.getMessages().get(0).getContent();
                String user = request.getMessages().get(request.getMessages().size() - 1).getContent();
                String agent = system.replaceFirst("(?s)^You are (\\w+)\\..*", "$1");
                calls.add(agent + ": " + user.lines().filter(l -> !l.isBlank()).reduce((a, b) -> b).orElse(""));
                if (agent.equals("Editor") && failEditorTimes.getAndDecrement() > 0) throw new IllegalStateException("model down");
                String content = switch (agent) {
                    case "Writer" -> "```json\n{\"draft\": \"tides and moons\"}\n```";
                    default -> "```json\n{\"thought\": \"ok\", \"final_answer\": \"edited: " + user.contains("with angle poetic") + "\"}\n```";
                };
                return LLMResponse.builder().content(content).model(name).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.empty();
            }
        };
    }

    /** A human who isn't at the keyboard: every question suspends the run. */
    static final HumanInterface AWAY = new HumanInterface() {
        @Override
        public String promptHuman(String message) {
            throw new IllegalStateException("not used");
        }

        @Override
        public String promptHuman(String stepId, String message) {
            throw new RunSuspended(stepId, message);
        }
    };

    private static HarnessExecutor executor(LLMClientFactory factory, RunJournal journal, HumanInterface human) {
        LoomScript script = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), factory);
        executor.setJournal(journal);
        executor.setHumanInterface(human);
        executor.initialize();
        return executor;
    }

    @Test
    void aHumanPromptSuspendsAndAResumedRunNeverRepeatsAModelCall() {
        List<String> calls = new CopyOnWriteArrayList<>();
        RunJournal journal = new FileRunJournal(dir.resolve("run.json"));

        RunSuspended waiting = assertThrows(RunSuspended.class,
                () -> executor(model(calls, new AtomicInteger()), journal, AWAY).executeWorkflow("Main", Map.of("topic", "the sea")));
        assertEquals("Which angle for tides and moons?", waiting.prompt());
        assertEquals(1, calls.size(), "only the Writer ran before the question");

        // Tomorrow, on another server: the answer arrives and a fresh executor resumes from the file.
        RunJournal reopened = new FileRunJournal(dir.resolve("run.json"));
        reopened.answer(waiting.stepId(), "poetic");
        HarnessExecutor resumed = executor(model(calls, new AtomicInteger()), reopened, AWAY);
        resumed.executeWorkflow("Main", Map.of("topic", "the sea"));

        assertEquals(2, calls.size(), "the Writer was replayed from the journal, only the Editor ran");
        assertTrue(calls.get(1).startsWith("Editor:"));
        assertEquals("edited: true", resumed.getContext().getVariable("final"));
        assertEquals("tides and moons", ((Map<?, ?>) resumed.getContext().getVariable("draft")).get("draft"));
    }

    @Test
    void aCrashedRunResumesFromItsLastRecordedStep() throws Exception {
        org.h2.jdbcx.JdbcDataSource db = new org.h2.jdbcx.JdbcDataSource();
        db.setURL("jdbc:h2:mem:journal;DB_CLOSE_DELAY=-1");
        JdbcRunJournal.createTable(db);
        List<String> calls = new CopyOnWriteArrayList<>();
        HumanInterface present = message -> "poetic";

        // The Editor's model is down: the run dies after the Writer and the human answer were recorded.
        RunJournal journal = new JdbcRunJournal(db, "run-1");
        assertThrows(RuntimeException.class,
                () -> executor(model(calls, new AtomicInteger(1)), journal, present).executeWorkflow("Main", Map.of("topic", "the sea")));
        assertEquals(List.of("Writer", "Editor"), calls.stream().map(c -> c.substring(0, c.indexOf(':'))).toList());

        // Another instance picks the run up: no second Writer call, no second question.
        calls.clear();
        HumanInterface mustNotAsk = message -> fail("the answer was already recorded");
        HarnessExecutor resumed = executor(model(calls, new AtomicInteger()), new JdbcRunJournal(db, "run-1"), mustNotAsk);
        resumed.executeWorkflow("Main", Map.of("topic", "the sea"));
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).startsWith("Editor:"));
        assertEquals("edited: true", resumed.getContext().getVariable("final"));
    }

    @Test
    void aToolCanWaitForAHumanMidStepWithoutBeingTreatedAsAnError() {
        String script = """
                agent Poster {
                    model: "test"
                    system: "You are Poster."
                    tools: [Publish]
                }
                workflow Main() {
                    delegate "Publish it" to Poster -> receipt retry 2
                }
                """;
        AtomicInteger llmCalls = new AtomicInteger();
        LLMClientFactory factory = name -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                llmCalls.incrementAndGet();
                String user = request.getMessages().get(request.getMessages().size() - 1).getContent();
                String content = user.contains("Observation:")
                        ? "```json\n{\"thought\": \"done\", \"final_answer\": \"posted\"}\n```"
                        : "```json\n{\"thought\": \"publish\", \"action\": \"publish\", \"action_input\": {}}\n```";
                return LLMResponse.builder().content(content).model(name).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.empty();
            }
        };
        RunJournal journal = RunJournal.inMemory();
        HarnessExecutor[] holder = new HarnessExecutor[1];
        ToolRegistry tools = new ToolRegistry();
        tools.register("Publish", new Tool() {
            public String getName() { return "publish"; }
            public String getDescription() { return "Publishes. Args: {}"; }
            public String execute(Map<String, Object> args) {
                return "approved=" + holder[0].awaitHuman("approve", "Publish now?");
            }
        });
        LoomScript parsed = new LoomParser(new Lexer(script).tokenize()).parseScript();

        holder[0] = new HarnessExecutor(parsed, tools, factory);
        holder[0].setJournal(journal);
        holder[0].initialize();
        RunSuspended waiting = assertThrows(RunSuspended.class, () -> holder[0].executeWorkflow("Main", Map.of()));
        assertEquals(1, llmCalls.get(), "suspension is not a failure: no retries were spent");
        assertTrue(waiting.stepId().endsWith("#approve"));

        journal.answer(waiting.stepId(), "yes");
        holder[0] = new HarnessExecutor(parsed, tools, factory);
        holder[0].setJournal(journal);
        holder[0].initialize();
        holder[0].executeWorkflow("Main", Map.of());
        assertEquals("posted", holder[0].getContext().getVariable("receipt"));
    }
}
