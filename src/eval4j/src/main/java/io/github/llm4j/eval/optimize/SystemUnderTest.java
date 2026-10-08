package io.github.llm4j.eval.optimize;

import io.github.llm4j.eval.dataset.EvalScenario;

/**
 * The thing being optimized, as a function: build the system from a {@link Candidate}'s text and
 * run one scenario, returning an {@code AgentResult}, {@code LLMResponse} or {@code String}.
 *
 * <pre>{@code
 * SystemUnderTest system = (candidate, scenario) ->
 *     ReActAgent.builder().llmClient(client).systemPrompt(candidate.get("system-prompt")).build()
 *         .run(scenario.input());
 * }</pre>
 *
 * <p>The optimizer calls this hundreds of times, possibly concurrently. Implementations must be
 * thread-safe and must not perform real side effects (send, write, charge): use test doubles for
 * such tools.
 */
@FunctionalInterface
public interface SystemUnderTest {

    Object run(Candidate candidate, EvalScenario scenario);
}
