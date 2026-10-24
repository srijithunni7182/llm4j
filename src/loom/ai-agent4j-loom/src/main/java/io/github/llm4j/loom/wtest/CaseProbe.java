package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.graph.GraphResult;
import io.github.llm4j.loom.graph.GraphService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one test case that exists only in memory (a captured case is built before it is written, and
 * what is unmasked must never reach a file), checks it against the script like {@code weave test
 * --check}, and says how it went.
 */
public final class CaseProbe {

    private CaseProbe() {}

    /**
     * @param problems what the checker found wrong with the case for this script (then nothing was
     *     run)
     * @param status how the run ended: completed, failed, suspended, ...
     * @param result what the workflow returned
     * @param route the steps that ran, as {@code weave graph} labels them
     * @param failures what the case's {@code expect} did not hold
     * @param journal what the run recorded, as a real run's journal
     */
    public record Result(
            List<String> problems,
            String status,
            Object result,
            List<String> route,
            List<String> failures,
            java.util.Map<String, io.github.llm4j.loom.runtime.RunJournal.Entry> journal) {}

    public static Result run(Path script, String workflow, ObjectNode caseBody) throws Exception {
        Path abs = script.toAbsolutePath().normalize();
        ObjectNode root = new YAMLMapper().createObjectNode();
        root.put("script", abs.toString());
        root.put("workflow", workflow);
        root.putArray("cases").add(caseBody);
        TestFile file =
                TestFile.parse(
                        abs.getParent().resolve("probe.test.yaml"),
                        new YAMLMapper().writeValueAsString(root));
        LoomScript loaded = new LoomLoader().load(abs.toString());
        GraphResult graph = new GraphService().graph(abs);
        List<String> problems =
                new ArrayList<>(new TestChecker(loaded, graph).check(List.of(file)));
        if (!problems.isEmpty() || file.cases().isEmpty())
            return new Result(problems, "", null, List.of(), List.of(), java.util.Map.of());
        TestFile.Case c = file.cases().get(0);
        CaseRunner.Outcome o = CaseRunner.run(abs, workflow, c);
        List<String> failures =
                Assertions.evaluate(c, o, graph, workflow).stream()
                        .map(Assertions.Failure::message)
                        .toList();
        return new Result(
                problems,
                o.status(),
                o.result(),
                o.recorder().route(),
                failures,
                o.journal() == null ? java.util.Map.of() : o.journal().all());
    }
}
