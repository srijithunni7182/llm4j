package io.github.llm4j.loom.travel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.debug.Stepper;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.wtest.LoomRun;
import io.github.llm4j.loom.wtest.LoomTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TEST_STRATEGY §3: random valid workflows, run for real with scripted models, then replayed from
 * their journals. The replay must start the same steps in the same order and end the same way (R1),
 * and the stepper, going forward and then back, must show the same state at every statement (R23).
 * A failing seed is printed with its script. 500 seeds on every build; {@code -Dloom.seeds=10000}
 * for the nightly run.
 */
public class GeneratedWorkflowsTest {

    @TempDir Path dir;

    private static final String[] ANSWERS = {"a", "b", "c"};

    /** A workflow made from a seed: bounded depth and size, conditions over earlier results. */
    public static final class Generator {
        private final Random random;
        private int variables;
        private final List<String> bound = new ArrayList<>();
        private int statements;

        public Generator(long seed) {
            this.random = new Random(seed);
        }

        public String script() {
            StringBuilder body = new StringBuilder();
            block(body, 1, 2 + random.nextInt(4));
            return "agent A { model: \"m\" system: \"x\" }\n"
                    + "agent B { model: \"m\" system: \"y\" }\n"
                    + "workflow Main(topic) {\n"
                    + body
                    + "    return \"end\"\n"
                    + "}\n";
        }

        private String indent(int depth) {
            return "    ".repeat(depth);
        }

        private void block(StringBuilder out, int depth, int count) {
            for (int i = 0; i < count && statements < 14; i++) statement(out, depth);
        }

        private void delegate(StringBuilder out, int depth) {
            String v = "v" + (variables++);
            String agent = random.nextBoolean() ? "A" : "B";
            out.append(indent(depth))
                    .append("delegate \"Do {topic}\" to ")
                    .append(agent)
                    .append(" -> ")
                    .append(v)
                    .append('\n');
            bound.add(v);
            statements++;
        }

        private void statement(StringBuilder out, int depth) {
            int kind = bound.isEmpty() || depth > 3 ? 0 : random.nextInt(6);
            switch (kind) {
                case 0, 1 -> delegate(out, depth);
                case 2 -> {
                    out.append(indent(depth))
                            .append("note \"step ")
                            .append(statements++)
                            .append("\"\n");
                }
                case 3 -> {
                    String v = bound.get(random.nextInt(bound.size()));
                    out.append(indent(depth))
                            .append("alt (")
                            .append(v)
                            .append(" == \"")
                            .append(ANSWERS[random.nextInt(3)])
                            .append("\") {\n");
                    statements++;
                    block(out, depth + 1, 1 + random.nextInt(2));
                    out.append(indent(depth)).append("} else {\n");
                    block(out, depth + 1, 1 + random.nextInt(2));
                    out.append(indent(depth)).append("}\n");
                }
                case 4 -> {
                    String v = "v" + (variables++);
                    out.append(indent(depth))
                            .append("loop until (")
                            .append(v)
                            .append(" == \"")
                            .append(ANSWERS[random.nextInt(3)])
                            .append("\") max ")
                            .append(1 + random.nextInt(3))
                            .append(" {\n");
                    statements++;
                    String agent = random.nextBoolean() ? "A" : "B";
                    out.append(indent(depth + 1))
                            .append("delegate \"Try {topic}\" to ")
                            .append(agent)
                            .append(" -> ")
                            .append(v)
                            .append('\n');
                    statements++;
                    out.append(indent(depth))
                            .append("} on_exhausted {\n")
                            .append(indent(depth + 1))
                            .append("note \"gave up\"\n")
                            .append(indent(depth))
                            .append("}\n");
                    bound.add(v);
                }
                default -> {
                    String name = "s" + (variables++);
                    out.append(indent(depth))
                            .append("set ")
                            .append(name)
                            .append(" = \"")
                            .append(ANSWERS[random.nextInt(3)])
                            .append("\"\n");
                    bound.add(name);
                    statements++;
                }
            }
        }

        public LoomTest answers(LoomTest t) {
            for (String agent : List.of("A", "B")) {
                String[] stream = new String[12];
                for (int i = 0; i < stream.length; i++) stream[i] = ANSWERS[random.nextInt(3)];
                t =
                        t.model(
                                agent,
                                stream[0],
                                stream[1],
                                java.util.Arrays.copyOfRange(stream, 2, stream.length));
            }
            return t.repeatLast();
        }
    }

    private static int seeds() {
        return Integer.getInteger("loom.seeds", 500);
    }

    @Test
    void everyGeneratedRunIsRederivedFromItsJournal() throws Exception {
        Set<String> kinds = new java.util.TreeSet<>();
        for (int seed = 0; seed < seeds(); seed++) {
            Generator g = new Generator(seed);
            String source = g.script();
            Path file = dir.resolve("g" + seed + ".loom");
            Files.writeString(file, source);
            LoomRun run;
            try {
                run = g.answers(LoomTest.of(file)).run("Main", Map.of("topic", "t"));
            } catch (RuntimeException e) {
                throw new AssertionError("seed " + seed + " did not run:\n" + source, e);
            }
            ObserveReplay.Result seen =
                    ObserveReplay.run(
                            new ObserveReplay.Options(file, "Main", Map.of("topic", "t"), Set.of()),
                            run.journal());
            String why = "seed " + seed + "\n" + source;
            assertThat(seen.steps()).as(why + "\nsteps").isEqualTo(run.stepIds());
            assertThat(seen.status()).as(why + "\nstatus").isEqualTo(run.status());
            assertThat(seen.variables().get("result")).as(why + "\nresult").isEqualTo(run.result());
            assertThat(seen.divergence()).as(why + "\ndivergence").isNull();
            assertThat(seen.unvisited()).as(why + "\nunvisited").isEmpty();
            seen.decisions().forEach(d -> kinds.add(d.kind()));
            Files.delete(file);
        }
        // the generator really does reach the choices it is there to test
        assertThat(kinds).contains("alt", "loop");
    }

    @Test
    void goingBackThroughAGeneratedRunShowsEachStateAsItWasGoingForward() throws Exception {
        int count = Math.min(seeds() / 10, 50);
        for (int seed = 0; seed < count; seed++) {
            Generator g = new Generator(1000 + seed);
            String source = g.script();
            Path file = dir.resolve("s" + seed + ".loom");
            Files.writeString(file, source);
            try (Stepper stepper =
                    Stepper.open(
                            new Stepper.Config(
                                    file,
                                    "Main",
                                    Map.of("topic", "t"),
                                    Set.of(),
                                    RunJournal.inMemory(),
                                    null))) {
                List<String> forward = new ArrayList<>();
                Stepper.State s = stepper.state();
                while (!s.finished()) {
                    forward.add(shown(s));
                    s = stepper.move(Stepper.Move.INTO);
                }
                for (int i = forward.size() - 1; i >= 0; i--) {
                    s = stepper.back();
                    assertThat(shown(s))
                            .as("seed " + (1000 + seed) + " statement " + i + "\n" + source)
                            .isEqualTo(forward.get(i));
                }
            }
        }
    }

    private static String shown(Stepper.State s) {
        StringBuilder sb =
                new StringBuilder(s.position().step())
                        .append(" | ")
                        .append(s.position().statement());
        s.variables()
                .forEach(
                        v ->
                                sb.append(" | ")
                                        .append(v.name())
                                        .append('=')
                                        .append(v.value())
                                        .append('@')
                                        .append(v.boundStep()));
        return sb.toString();
    }
}
