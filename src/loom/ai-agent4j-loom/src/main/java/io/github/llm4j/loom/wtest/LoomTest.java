package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.graph.GraphResult;
import io.github.llm4j.loom.graph.GraphService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Testing a workflow from Java, with the same engine as {@code weave test}: the models,
 * classifiers, tools, tasks and people are scripted, failures are injected, time is virtual, and
 * nothing is spent. A case written here and the same case written in a {@code *.test.yaml} file run
 * through one implementation and give one result.
 *
 * <pre>{@code
 * LoomRun run = LoomTest.of(Path.of("triage.loom"))
 *         .classifier("Intent", Map.of("kind", "order_status", "urgent", "no"))
 *         .model("Replier", "Your parcel shipped yesterday.")
 *         .failing("Replier", Failure.times(2, "timeout"))
 *         .run("Main", Map.of("message", "where is my parcel?"));
 *
 * LoomRunAssert.assertThat(run).completed().returned("Your parcel shipped yesterday.").called("Replier", 3);
 * }</pre>
 *
 * <p>{@link #cases(Path)} turns a test file into cases a JUnit {@code @TestFactory} can run.
 */
public final class LoomTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path script;
    private final ObjectNode body = JSON.createObjectNode();
    private String workflow;

    private LoomTest(Path script) {
        this.script = script;
    }

    public static LoomTest of(Path script) {
        return new LoomTest(script);
    }

    private ObjectNode map(String key) {
        JsonNode n = body.get(key);
        if (n instanceof ObjectNode o) return o;
        return body.putObject(key);
    }

    // ---- what the models say

    /** The agent answers this text on every call. */
    public LoomTest model(String agent, String answer) {
        map("models").put(agent, answer);
        return this;
    }

    /** The agent answers these in order, one for each call. */
    public LoomTest model(String agent, String first, String second, String... more) {
        ArrayNode list = map("models").putArray(agent);
        list.add(first).add(second);
        for (String m : more) list.add(m);
        return this;
    }

    /** The agent answers a typed answer (for an agent with an {@code output_schema}). */
    public LoomTest typed(String agent, Map<String, ?> answer) {
        map("models").set(agent, JSON.valueToTree(answer));
        return this;
    }

    /** The agent calls a tool on its next call: {@code { call: tool, with: args }}. */
    public LoomTest calls(String agent, String tool, Map<String, ?> args, String thenAnswer) {
        ArrayNode list = map("models").putArray(agent);
        ObjectNode call = list.addObject();
        call.put("call", tool);
        call.set("with", JSON.valueToTree(args));
        list.add(thenAnswer);
        return this;
    }

    /** The classifier answers a label for each question (and may give a {@code confidence}). */
    public LoomTest classifier(String name, Map<String, ?> answers) {
        map("models").set(name, JSON.valueToTree(answers));
        return this;
    }

    /** The tool returns this text on every call. */
    public LoomTest tool(String name, String returns) {
        map("tools").put(name, returns);
        return this;
    }

    /** The task returns these fields. */
    public LoomTest task(String name, Map<String, ?> result) {
        map("tasks").set(name, JSON.valueToTree(result));
        return this;
    }

    /** The people answer questions in this order; with none left the run is suspended. */
    public LoomTest humans(String... answers) {
        ArrayNode list = body.putArray("humans");
        for (String a : answers) list.add(a);
        return this;
    }

    /** After a suspension, the answers given in turn, continuing the run from its journal. */
    public LoomTest resumeWith(String... answers) {
        ArrayNode list = body.putArray("resume");
        for (String a : answers) list.add(a);
        return this;
    }

    // ---- what goes wrong

    /** A failure a target (agent, classifier, tool or task) has on a schedule. */
    public record Failure(Integer times, List<Integer> onCalls, boolean always, String category) {
        public static Failure times(int n, String category) {
            return new Failure(n, List.of(), false, category);
        }

        public static Failure onCalls(String category, Integer... calls) {
            return new Failure(null, List.of(calls), false, category);
        }

        public static Failure always(String category) {
            return new Failure(null, List.of(), true, category);
        }
    }

    public LoomTest failing(String target, Failure failure) {
        ObjectNode spec = map("fail").putObject(target);
        if (failure.times() != null) spec.put("times", failure.times());
        if (!failure.onCalls().isEmpty()) {
            ArrayNode calls = spec.putArray("on_calls");
            failure.onCalls().forEach(calls::add);
        }
        if (failure.always()) spec.put("always", true);
        spec.put("category", failure.category());
        return this;
    }

    // ---- how it runs

    /** The workflow to run (needed when the script has several). */
    public LoomTest workflow(String name) {
        this.workflow = name;
        return this;
    }

    /** An unscripted call answers neutrally instead of failing the run. */
    public LoomTest lenient() {
        body.put("lenient", true);
        return this;
    }

    /** A list that runs out repeats its last answer. */
    public LoomTest repeatLast() {
        body.put("repeat", "last");
        return this;
    }

    /** Runs the workflow named {@code name} with these inputs. */
    public LoomRun run(String name, Map<String, String> input) {
        return workflow(name).run(input);
    }

    /** Runs the workflow (the only one, or the one named with {@link #workflow}). */
    public LoomRun run(Map<String, String> input) {
        try {
            LoomScript loaded = new LoomLoader().load(script.toString());
            String name = workflow;
            if (name == null) {
                if (loaded.getWorkflows().size() != 1)
                    throw new IllegalStateException(
                            "which workflow? the script has "
                                    + loaded.getWorkflows().stream()
                                            .map(w -> w.getName())
                                            .toList());
                name = loaded.getWorkflows().get(0).getName();
            }
            ObjectNode caseBody = body.deepCopy();
            ObjectNode in = caseBody.putObject("input");
            input.forEach(in::put);
            TestFile.Case c = new TestFile.Case("java", "java case", caseBody);
            CaseRunner.Outcome outcome = CaseRunner.run(script, name, c);
            GraphResult graph = new GraphService().graph(script);
            return new LoomRun(outcome, graph, name);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    // ---- test files

    /** One case of a test file, ready to be run as a test. */
    public static final class NamedCase {
        private final String name;
        private final TestRunner.Suite suite;
        private final TestFile.Case source;

        private NamedCase(String name, TestRunner.Suite suite, TestFile.Case source) {
            this.name = name;
            this.suite = suite;
            this.source = source;
        }

        public String name() {
            return name;
        }

        /** Runs the case; throws {@link AssertionError} with every difference when it fails. */
        public void run() {
            TestRunner.CaseResult r = TestRunner.runOne(suite, source);
            if (!r.passed())
                throw new AssertionError(
                        "case \""
                                + name
                                + "\" failed:\n  - "
                                + String.join("\n  - ", r.failures()));
        }
    }

    /**
     * The cases of a test file (or the {@code *.test.yaml} files of a folder) for the script they
     * name. Map each to a JUnit test:
     *
     * <pre>{@code
     * @TestFactory
     * Stream<DynamicTest> triage() {
     *     return LoomTest.cases(Path.of("tests")).stream()
     *             .map(c -> DynamicTest.dynamicTest(c.name(), c::run));
     * }
     * }</pre>
     *
     * @throws IllegalArgumentException when the files do not fit the script (the problems are
     *     listed)
     */
    public static List<NamedCase> cases(Path testFileOrFolder) {
        return cases(null, testFileOrFolder);
    }

    /** As {@link #cases(Path)}, for this script rather than the one the test files name. */
    public static List<NamedCase> cases(Path script, Path testFileOrFolder) {
        try {
            TestRunner.Suite suite = TestRunner.load(script, List.of(testFileOrFolder));
            if (!suite.problems().isEmpty())
                throw new IllegalArgumentException(
                        "the test files do not fit the script:\n  - "
                                + String.join("\n  - ", suite.problems()));
            List<NamedCase> out = new ArrayList<>();
            for (TestFile f : suite.files())
                for (TestFile.Case c : f.cases()) out.add(new NamedCase(c.name(), suite, c));
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}
