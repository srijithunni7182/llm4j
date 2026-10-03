package io.github.llm4j.evalreport.format;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.evalreport.format.model.Ev;
import io.github.llm4j.evalreport.format.model.RunMeta;
import io.github.llm4j.evalreport.format.model.ScenarioDef;
import io.github.llm4j.evalreport.format.model.TestOutcome;
import java.util.List;

/** A fully read run bundle. {@code warnings} lists lines that were skipped or repaired. */
public record RunBundle(
        RunMeta run,
        List<Ev> evaluations,
        List<ScenarioDef> scenarios,
        List<TestOutcome> tests,
        List<JsonNode> traces,
        List<JsonNode> optimizations,
        List<String> warnings) {}
