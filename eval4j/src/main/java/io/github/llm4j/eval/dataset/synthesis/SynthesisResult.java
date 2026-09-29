package io.github.llm4j.eval.dataset.synthesis;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.List;

/** The kept scenarios plus a {@link SynthesisReport} accounting for everything that was not. */
public record SynthesisResult(List<EvalScenario> scenarios, SynthesisReport report) {}
