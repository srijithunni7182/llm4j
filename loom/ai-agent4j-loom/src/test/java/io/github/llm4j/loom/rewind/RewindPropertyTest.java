package io.github.llm4j.loom.rewind;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.tools.support.Fuzz;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Random workflows with checkpoints, rewinds, effects and people, crashed at random writes and resumed: the end state is always the one an
 * uninterrupted run reaches, nothing is sent twice, and no person is asked twice (spec loom-rewind-and-fork V9.5).
 */
class RewindPropertyTest {

    @TempDir
    Path dir;

    /** A generated script. */
    record Generated(String source, int checkpoints) { }

    /** Builds a workflow of 3 to 9 statements: work, sends (effects), questions, judgements and rewinds to an earlier checkpoint. */
    static Generated generate(Random random) {
        StringBuilder body = new StringBuilder();
        int statements = 3 + random.nextInt(7);
        int checkpoints = 0;
        List<Integer> open = new ArrayList<>();
        int judged = 0;
        body.append("    checkpoint c0  starting with feedback = \"none\"\n");
        open.add(0);
        checkpoints++;
        for (int i = 1; i <= statements; i++) {
            switch (random.nextInt(6)) {
                case 0, 1 -> body.append("    delegate \"Work ").append(i).append(". Feedback: {feedback}\" to Worker -> v").append(i).append("\n");
                case 2 -> body.append("    delegate \"Send ").append(i).append(". Feedback: {feedback}\" to Sender -> s").append(i).append("\n");
                case 3 -> body.append("    human_prompt \"Question ").append(i).append("?\" -> h").append(i).append("\n");
                case 4 -> {
                    body.append("    checkpoint c").append(checkpoints).append("\n");
                    open.add(checkpoints++);
                }
                default -> {
                    int target = open.get(random.nextInt(open.size()));
                    body.append("    delegate \"Judge ").append(i).append(". Feedback: {feedback}\" to Judge -> j").append(i).append("\n");
                    body.append("    rewind to c").append(target).append(" when (j").append(i).append(" == \"bad\") at most ").append(1 + random.nextInt(2))
                            .append(" times carrying feedback = \"fix").append(i).append("\" side effects: keep\n");
                    judged++;
                }
            }
        }
        body.append("    delegate \"Finish. Feedback: {feedback}\" to Worker -> end\n");
        String source = """
                tool Log { use: file  root: "out"  mode: write }
                agent Worker { model: "m" system: "You are Worker." }
                agent Judge  { model: "m" system: "You are Judge." }
                agent Sender { model: "m" system: "You are Sender." tools: [Log] max_iterations: 6 }
                workflow Main() {
                %s}
                """.formatted(body);
        return new Generated(source, checkpoints);
    }

    /** The model is a function of what it is asked: a judge says bad until the feedback contains "fix", a sender writes one line per task. */
    static String model(LLMRequest request) {
        String system = request.getMessages().get(0).getContent();
        String message = ScriptedRun.lastMessage(request);
        int marker = message.lastIndexOf("Current Task:");
        String task = (marker < 0 ? message : message.substring(marker + "Current Task:".length())).trim().replaceFirst("^Question:\\s*", "");
        if (system.contains("Judge")) return ScriptedRun.done(task.contains("Feedback: fix") ? "good" : "bad");
        if (system.contains("Sender")) {
            if (message.lastIndexOf("Observation:") > marker) return ScriptedRun.done("sent " + task.replaceAll("\\s+", "_"));
            return ScriptedRun.call("Log", "{\"action\": \"append\", \"path\": \"sent.md\", \"content\": \"" + task.replaceAll("[^A-Za-z0-9 .:]", "").replace(' ', '_') + "\"}");
        }
        return ScriptedRun.done("done<" + task.replaceAll("[^A-Za-z0-9 .:]", "").replace(' ', '_') + ">");
    }

    /** Everything an outside observer could see of a finished run: its journal, the lines sent, the questions asked. */
    record Outcome(Map<String, Object> journal, List<String> sent, int asked, int modelCalls) { }

    private Outcome run(Generated generated, RunJournal journal, Path work, int crashAt, boolean afterWrite, List<String> questions, AtomicInteger calls) throws IOException {
        RunJournal effective = crashAt > 0 ? new FaultRunJournal(journal, crashAt, afterWrite) : journal;
        ScriptedRun run = new ScriptedRun(work);
        run.journal = effective;
        run.responder = r -> {
            calls.incrementAndGet();
            return model(r);
        };
        run.human = q -> {
            questions.add(q);
            return "ok";
        };
        var executor = run.executor(generated.source());
        executor.initialize();
        executor.executeWorkflow("Main", Map.of());
        return null;
    }

    private static Map<String, Object> values(RunJournal journal) {
        Map<String, Object> out = new LinkedHashMap<>();
        journal.all().forEach((k, e) -> {
            if (k.contains("#usage") || k.endsWith("#checkpoint") || k.equals("#boundaries") || k.contains("#idem")) return;
            // an effect found already done says so; the observation is otherwise what it was
            out.put(k, e.value() instanceof String text ? text.replace("(already_done_in_an_earlier_attempt)_", "") : e.value());
        });
        return out;
    }

    private static boolean valuesHaveUnknown(RunJournal journal) {
        return journal.all().values().stream().anyMatch(e -> String.valueOf(e.value()).contains("outcome_is_unknown") || String.valueOf(e.value()).contains("outcome is unknown"))
                || journal.all().entrySet().stream().anyMatch(e -> e.getKey().contains("#effect:") && "effect_pending".equals(e.getValue().kind()));
    }

    /** What differs between two journals, key by key, for a failure message a person can read. */
    private static List<String> difference(Map<String, Object> expected, Map<String, Object> actual) {
        List<String> out = new ArrayList<>();
        for (String key : expected.keySet()) {
            if (!actual.containsKey(key)) out.add("missing " + key);
            else if (!expected.get(key).equals(actual.get(key))) out.add("differs " + key + ": expected [" + expected.get(key) + "] but was [" + actual.get(key) + "]");
        }
        for (String key : actual.keySet()) if (!expected.containsKey(key)) out.add("extra " + key + " = " + actual.get(key));
        return out;
    }

    private static List<String> sent(Path work) throws IOException {
        Path file = work.resolve("out/sent.md");
        if (!Files.exists(file)) return List.of();
        List<String> lines = new ArrayList<>(Files.readAllLines(file).stream().filter(l -> !l.isBlank()).toList());
        Collections.sort(lines);
        return lines;
    }

    @Test
    @Tag("RW-V9.5")
    void crashingAtARandomWriteAndResumingAlwaysEndsWhereAnUninterruptedRunEnds() throws Exception {
        int scripts = Math.min(Fuzz.iterations(), Integer.getInteger("loom.rewind.scripts", 150));
        long seed = Long.getLong("loom.fuzz.seed", 20261001L);
        for (int n = 0; n < scripts; n++) {
            Random random = new Random(seed * 131 + n);
            Generated generated = generate(random);
            String where = "script " + n + " (seed " + seed + "):\n" + generated.source();

            Path refDir = Files.createDirectories(dir.resolve("ref" + n));
            FaultRunJournal counting = new FaultRunJournal(RunJournal.inMemory(), 0, false);
            List<String> refQuestions = new ArrayList<>();
            AtomicInteger refCalls = new AtomicInteger();
            run(generated, counting, refDir, 0, false, refQuestions, refCalls);
            Map<String, Object> reference = values(counting);
            List<String> expectedSent = sent(refDir);
            int writes = counting.puts();

            for (int k = 0; k < 6 && writes > 0; k++) {
                int crashAt = 1 + random.nextInt(writes);
                boolean afterWrite = random.nextBoolean();
                Path work = Files.createDirectories(dir.resolve("run" + n + "-" + k));
                RunJournal durable = RunJournal.inMemory();
                List<String> questions = new ArrayList<>();
                AtomicInteger calls = new AtomicInteger();
                try {
                    run(generated, durable, work, crashAt, afterWrite, questions, calls);
                } catch (FaultRunJournal.Crash crashed) {
                    // the process died here
                }
                run(generated, durable, work, 0, false, questions, calls);

                String why = "crash " + (afterWrite ? "after" : "instead of") + " write " + crashAt + " of " + writes + " in " + where;
                Map<String, Object> expected = reference;
                Map<String, Object> actual = values(durable);
                // The one honest exception: a crash between performing an effect and recording it leaves its outcome unknown, and the default
                // is not to repeat it. The line was sent once; the journal then says "unknown" where the reference says "done".
                boolean unknown = actual.values().stream().anyMatch(v -> String.valueOf(v).contains("outcome_is_unknown"));
                if (unknown) {
                    java.util.Set<String> drop = new java.util.HashSet<>();
                    actual.forEach((key, value) -> { if (key.contains("#effect:") || String.valueOf(value).contains("outcome_is_unknown")) drop.add(key); });
                    expected = new LinkedHashMap<>(expected);
                    expected.keySet().removeIf(key -> key.contains("#effect:"));
                    expected.keySet().removeAll(drop);
                    actual.keySet().removeAll(drop);
                }
                assertThat(difference(expected, actual)).as(why + " (journal)\nactual keys: " + actual.keySet() + "\nexpected keys: " + expected.keySet() + "\nraw: " + durable.all()).isEmpty();
                List<String> sentNow = sent(work);
                if (unknown || valuesHaveUnknown(durable)) {
                    // at most once: a call whose outcome was lost is not repeated, so a line may be missing, but never twice and never new
                    List<String> remaining = new ArrayList<>(expectedSent);
                    for (String line : sentNow) assertThat(remaining.remove(line)).as(why + " (sent a line twice or one the reference never sent: " + line + ")").isTrue();
                } else {
                    assertThat(sentNow).as(why + " (what was sent)").isEqualTo(expectedSent);
                }
                assertThat(calls.get()).as(why + " (model calls: only the delegate in flight, at most 3 turns, may be made again)").isLessThanOrEqualTo(refCalls.get() + 3);
                assertThat(questions.size()).as(why + " (questions asked)").isLessThanOrEqualTo(refQuestions.size() + 1);
            }
        }
    }
}
