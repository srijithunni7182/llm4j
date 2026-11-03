package io.github.llm4j.loom.starters;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Starter 16 (phase 25, R6): what a long agent has done and has still to do is a value the script
 * owns. The things a hermetic {@code weave test} case cannot assert are asserted here: the worker's
 * requests hold no transcript, the progress of one round is in the task of the next, and a run
 * that stopped half way continues from the recorded progress instead of starting again.
 */
class StepByStepStarterTest {

    private static final Path SCRIPT =
            Path.of("../starters/16-step-by-step-agent/main.loom").toAbsolutePath().normalize();

    /** A scripted model: the worker reports in prose, the extractor writes the progress. */
    private static class Fake implements LLMClient {
        final List<String> workerTasks = Collections.synchronizedList(new ArrayList<>());
        final List<String> workerRequests = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger extractCalls = new AtomicInteger();
        final AtomicInteger workerCalls = new AtomicInteger();
        final int finishAtRound;

        Fake(int finishAtRound) {
            this.finishAtRound = finishAtRound;
        }

        static boolean isExtraction(LLMRequest request) {
            StringBuilder all = new StringBuilder();
            request.getMessages().forEach(m -> all.append(m.getContent()));
            return all.toString().contains("You are a field extractor.");
        }

        @Override
        public LLMResponse chat(LLMRequest request) {
            StringBuilder all = new StringBuilder();
            request.getMessages().forEach(m -> all.append(m.getContent()).append('\n'));
            String text = all.toString();
            if (text.contains("You are a field extractor.")) {
                int round = extractCalls.incrementAndGet();
                boolean finished = round >= finishAtRound;
                String done = String.join(", ", Collections.nCopies(round, "\"step\""));
                return answer(
                        "{\"done\": [" + done + "], \"next\": \"step " + (round + 1) + "\", \"finished\": " + finished + "}");
            }
            int round = workerCalls.incrementAndGet();
            workerTasks.add(text);
            workerRequests.add(text);
            return answer("I did step " + round + ". Marker-" + round + ".");
        }

        private static LLMResponse answer(String content) {
            return LLMResponse.builder().content(content).model("test").build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            return Stream.empty();
        }
    }

    private static HarnessExecutor executor(Fake fake, RunJournal journal) throws Exception {
        LoomScript script = new LoomLoader().load(SCRIPT.toString());
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), model -> fake);
        executor.setBaseDir(SCRIPT.getParent());
        if (journal != null) executor.setJournal(journal);
        executor.initialize();
        return executor;
    }

    @Test
    void theWorkerSeesNoTranscriptAndEachRoundIsToldWhatTheLastOneDid() throws Exception {
        Fake fake = new Fake(3);
        HarnessExecutor executor = executor(fake, null);

        executor.executeWorkflow("Main", Map.of("goal", "write the report"));

        assertThat(fake.workerRequests).hasSize(3);
        // no transcript: the earlier rounds' reports are not in a later request as conversation history
        for (String request : fake.workerRequests)
            assertThat(request).doesNotContain("Conversation History").doesNotContain("Marker-");
        // yet the progress travels: round 2 is told what round 1 recorded, and what to do next
        assertThat(fake.workerRequests.get(0)).contains("plan the first step");
        assertThat(fake.workerRequests.get(1)).contains("DO THIS ONE STEP NOW: step 2");
        assertThat(fake.workerRequests.get(2)).contains("DO THIS ONE STEP NOW: step 3");
        assertThat(executor.getContext().getAll().get("result").toString()).contains("step");
    }

    @Test
    void aRunThatStoppedHalfWayContinuesFromTheRecordedProgress() throws Exception {
        // What a file journal holds at the moment the process dies in round 3: the first run is
        // copied at that moment (rounds 1 and 2 recorded, round 3 not).
        RunJournal live = RunJournal.inMemory();
        RunJournal onDisk = RunJournal.inMemory();
        Fake first =
                new Fake(4) {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        if (workerCalls.get() == 2 && !Fake.isExtraction(request))
                            live.all().forEach(onDisk::put); // the process dies here
                        return super.chat(request);
                    }
                };
        executor(first, live).executeWorkflow("Main", Map.of("goal", "write the report"));
        assertThat(first.extractCalls.get()).isEqualTo(4);

        Fake second = new Fake(2); // a new process over what was on disk; two more rounds finish
        executor(second, onDisk).executeWorkflow("Main", Map.of("goal", "write the report"));

        // rounds 1 and 2 were not asked again: only the rounds after the recorded progress ran
        assertThat(second.workerCalls.get()).isEqualTo(2);
        assertThat(second.workerTasks.get(0)).contains("DO THIS ONE STEP NOW: step 3");
    }
}
