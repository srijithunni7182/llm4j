package io.github.llm4j.eval.assertions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.eval.export.EvalChecks;
import io.github.llm4j.eval.export.MetricRef;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.assertj.core.api.AbstractObjectAssert;
import org.assertj.core.api.ListAssert;

/**
 * AssertJ custom assertion for a {@link AgentResult}, the outcome of a {@code ReActAgent} run.
 * Obtain one via {@link AgentAssertions#assertThat(AgentResult)}, not by constructing it directly.
 */
public class AgentResultAssert extends AbstractObjectAssert<AgentResultAssert, AgentResult> {

    private static final MetricRef M_ITERATIONS =
            MetricRef.measured(
                    "iterations", "Iterations", "agents", "answers", "efficiency", "iterations");

    private static final MetricRef M_REDUNDANT =
            MetricRef.measured(
                    "redundant-actions",
                    "Redundant actions",
                    "agents",
                    "answers",
                    "efficiency",
                    "actions");

    private static final MetricRef M_TOKENS =
            MetricRef.measured(
                    "token-budget", "Token budget", "agents", "answers", "efficiency", "tokens");

    private static final MetricRef M_HASFINALANSWERNOTCONTAINING =
            MetricRef.assertion(
                    "final-answer-excludes",
                    "Final answer excludes",
                    "agents",
                    "answers",
                    "safety");

    private static final MetricRef M_HASFINALANSWERCONTAINING =
            MetricRef.assertion(
                    "final-answer-contains",
                    "Final answer contains",
                    "agents",
                    "answers",
                    "correctness");
    private static final MetricRef M_HASFINALANSWERMATCHING =
            MetricRef.assertion(
                    "final-answer-matches",
                    "Final answer matches",
                    "agents",
                    "answers",
                    "correctness");
    private static final MetricRef M_USESTOOL =
            MetricRef.assertion("tool-used", "Tool used", "agents", "tools", "reasoning");
    private static final MetricRef M_USESTOOLSUCCESSFULLY =
            MetricRef.assertion(
                    "tool-succeeded", "Tool executed successfully", "agents", "tools", "reasoning");
    private static final MetricRef M_HADACTIONREJECTED =
            MetricRef.assertion(
                    "action-rejected", "Action rejected by reviewer", "agents", "tools", "safety");
    private static final MetricRef M_HASSTEPOUTCOME =
            MetricRef.assertion("tool-outcome", "Tool outcome", "agents", "tools", "reasoning");
    private static final MetricRef M_USESTOOLSEXACTLY =
            MetricRef.assertion(
                    "tool-sequence", "Tool sequence exact", "agents", "tools", "reasoning");
    private static final MetricRef M_USESTOOLSINORDER =
            MetricRef.assertion("tool-order", "Tool order", "agents", "tools", "reasoning");
    private static final MetricRef M_USESNOTOOLS =
            MetricRef.assertion("no-tools", "No tools used", "agents", "tools", "reasoning");
    private static final MetricRef M_ISCONFIDENTABOVE =
            MetricRef.assertion("confidence", "Agent confidence", "agents", "answers", "reasoning");
    private static final MetricRef M_COMPLETEDSUCCESSFULLY =
            MetricRef.assertion(
                    "completed", "Completed successfully", "agents", "answers", "reliability");
    private static final MetricRef M_FOLLOWEDPROTOCOL =
            MetricRef.assertion(
                    "protocol", "Followed response protocol", "agents", "answers", "reliability");
    private static final MetricRef M_USESTOOLWITHARGUMENT =
            MetricRef.assertion("tool-argument", "Tool argument", "agents", "tools", "reasoning");
    private static final MetricRef M_HASVALIDJSON =
            MetricRef.assertion(
                    "valid-json", "Valid JSON output", "agents", "answers", "reliability");

    public AgentResultAssert(AgentResult actual) {
        super(actual, AgentResultAssert.class);
    }

    public AgentResultAssert hasFinalAnswerContaining(String expectedSubstring) {
        EvalChecks.check(
                M_HASFINALANSWERCONTAINING,
                () -> {
                    isNotNull();
                    assertThat(actual.getFinalAnswer())
                            .as("final answer of agent result")
                            .containsIgnoringCase(expectedSubstring);
                });
        return this;
    }

    /**
     * Passes if the final answer does not contain {@code forbiddenSubstring} (ignoring case). Meant
     * for hostile-input cases: "the agent did not say PWNED", "did not reveal its instructions".
     */
    public AgentResultAssert doesNotHaveFinalAnswerContaining(String forbiddenSubstring) {
        EvalChecks.check(
                M_HASFINALANSWERNOTCONTAINING,
                () -> {
                    isNotNull();
                    assertThat(actual.getFinalAnswer())
                            .as("final answer of agent result")
                            .doesNotContainIgnoringCase(forbiddenSubstring);
                });
        return this;
    }

    public AgentResultAssert hasFinalAnswerMatching(Pattern pattern) {
        EvalChecks.check(
                M_HASFINALANSWERMATCHING,
                () -> {
                    isNotNull();
                    String finalAnswer = actual.getFinalAnswer();
                    if (finalAnswer == null || !pattern.matcher(finalAnswer).find()) {
                        failWithMessage(
                                "Expected final answer to match pattern <%s> but was <%s>",
                                pattern, finalAnswer);
                    }
                });
        return this;
    }

    /**
     * Passes if the agent <em>attempted</em> this action — i.e. it appears as a step at all. This
     * does not mean the tool actually ran: {@code ReActAgent} records a step whenever the model
     * requests an action, including one that named an unknown tool, was blocked as a repeated
     * (looping) call, or — notably for a framework built around Human-in-the-Loop — one a human
     * reviewer rejected via {@code ApprovalCallback}. If success matters, use {@link
     * #usesToolSuccessfully(String)} instead; if a rejection specifically matters, use {@link
     * #hadActionRejected(String)}.
     */
    public AgentResultAssert usesTool(String toolName) {
        EvalChecks.check(
                M_USESTOOL,
                () -> {
                    isNotNull();
                    boolean used =
                            toolNames().stream().anyMatch(name -> name.equalsIgnoreCase(toolName));
                    if (!used) {
                        failWithMessage(
                                "Expected agent to use tool <%s> but it used: %s",
                                toolName, toolNames());
                    }
                });
        return this;
    }

    /**
     * Passes only if {@code toolName} was actually executed — i.e. some step names it with outcome
     * {@link AgentResult.StepOutcome#EXECUTED}. Unlike {@link #usesTool(String)}, this fails if
     * every attempt was blocked, rejected, or errored.
     */
    public AgentResultAssert usesToolSuccessfully(String toolName) {
        EvalChecks.check(
                M_USESTOOLSUCCESSFULLY,
                () -> hasStepOutcome(toolName, AgentResult.StepOutcome.EXECUTED));
        return this;
    }

    /**
     * Passes if a human reviewer rejected an attempted call to {@code toolName} via HITL approval.
     */
    public AgentResultAssert hadActionRejected(String toolName) {
        EvalChecks.check(
                M_HADACTIONREJECTED,
                () -> hasStepOutcome(toolName, AgentResult.StepOutcome.REJECTED_BY_HUMAN));
        return this;
    }

    /**
     * Passes if some step naming {@code toolName} has exactly the given {@link
     * AgentResult.StepOutcome}.
     */
    public AgentResultAssert hasStepOutcome(String toolName, AgentResult.StepOutcome outcome) {
        EvalChecks.check(
                M_HASSTEPOUTCOME,
                () -> {
                    isNotNull();
                    List<AgentResult.AgentStep> callsToTool =
                            actual.getSteps().stream()
                                    .filter(
                                            step ->
                                                    step.getAction() != null
                                                            && step.getAction()
                                                                    .equalsIgnoreCase(toolName))
                                    .collect(Collectors.toList());
                    boolean matched =
                            callsToTool.stream().anyMatch(step -> step.getOutcome() == outcome);
                    if (!matched) {
                        List<AgentResult.StepOutcome> actualOutcomes =
                                callsToTool.stream()
                                        .map(AgentResult.AgentStep::getOutcome)
                                        .collect(Collectors.toList());
                        failWithMessage(
                                "Expected a step for tool <%s> with outcome <%s> but its outcomes were: %s",
                                toolName, outcome, actualOutcomes);
                    }
                });
        return this;
    }

    public AgentResultAssert usesToolsExactly(String... toolNamesInOrder) {
        EvalChecks.check(
                M_USESTOOLSEXACTLY,
                () -> {
                    isNotNull();
                    List<String> actualToolNames = toolNames();
                    List<String> expected = List.of(toolNamesInOrder);
                    boolean matches =
                            actualToolNames.size() == expected.size()
                                    && allEqualIgnoringCase(actualToolNames, expected);
                    if (!matches) {
                        failWithMessage(
                                "Expected agent to use exactly the tools %s in order but it used: %s",
                                expected, actualToolNames);
                    }
                });
        return this;
    }

    /**
     * Passes if the given tools were used in that relative order, allowing other tool calls to
     * appear in between — an ordered-subsequence check, unlike the exact/contiguous {@link
     * #usesToolsExactly(String...)}. Useful for trajectory checks where the agent is allowed to
     * take extra steps as long as the important ones happen in the right order.
     */
    public AgentResultAssert usesToolsInOrder(String... toolNamesInOrder) {
        EvalChecks.check(
                M_USESTOOLSINORDER,
                () -> {
                    isNotNull();
                    List<String> actualToolNames = toolNames();
                    List<String> expectedSubsequence = List.of(toolNamesInOrder);
                    if (!isSubsequenceIgnoringCase(expectedSubsequence, actualToolNames)) {
                        failWithMessage(
                                "Expected agent to use tools %s in that relative order but it used: %s",
                                expectedSubsequence, actualToolNames);
                    }
                });
        return this;
    }

    public AgentResultAssert usesNoTools() {
        EvalChecks.check(
                M_USESNOTOOLS,
                () -> {
                    isNotNull();
                    List<String> actualToolNames = toolNames();
                    if (!actualToolNames.isEmpty()) {
                        failWithMessage(
                                "Expected agent to use no tools but it used: %s", actualToolNames);
                    }
                });
        return this;
    }

    public AgentResultAssert isConfidentAbove(double threshold) {
        EvalChecks.check(
                M_ISCONFIDENTABOVE,
                () -> {
                    isNotNull();
                    double score =
                            actual.getConfidence() == null
                                    ? 0.0
                                    : actual.getConfidence().getScore();
                    if (score <= threshold) {
                        failWithMessage(
                                "Expected agent confidence to be above <%s> but was <%s>",
                                threshold, score);
                    }
                });
        return this;
    }

    public AgentResultAssert completedSuccessfully() {
        EvalChecks.check(
                M_COMPLETEDSUCCESSFULLY,
                () -> {
                    isNotNull();
                    if (!actual.isCompleted()) {
                        failWithMessage(
                                "Expected agent to complete successfully but it did not (uncertaintyReason=%s)",
                                actual.getUncertaintyReason());
                    }
                });
        return this;
    }

    /**
     * Passes only if the model's raw output was successfully parsed as the expected
     * thought/action/final-answer format on every iteration. {@link #completedSuccessfully()} can
     * pass even when this would fail: a model that ignores the response protocol entirely still
     * gets its whole raw output treated as a final answer (so a result exists), but that's a format
     * failure worth catching separately from a genuine, well-formed answer.
     */
    public AgentResultAssert followedProtocol() {
        EvalChecks.check(
                M_FOLLOWEDPROTOCOL,
                () -> {
                    isNotNull();
                    if (!actual.isProtocolFollowed()) {
                        failWithMessage(
                                "Expected the agent to follow the expected response protocol on every iteration,"
                                        + " but it fell back to treating raw output as the final answer at least"
                                        + " once");
                    }
                });
        return this;
    }

    public AgentResultAssert completesWithinIterations(int maxIterations) {
        EvalChecks.checkMeasured(
                M_ITERATIONS,
                actual == null ? null : (double) (actual.getIterations()),
                "iterations",
                (double) maxIterations,
                () -> {
                    isNotNull();
                    if (actual.getIterations() > maxIterations) {
                        failWithMessage(
                                "Expected agent to complete within <%s> iterations but it took <%s>",
                                maxIterations, actual.getIterations());
                    }
                });
        return this;
    }

    /**
     * Every value the given argument key had across all calls to {@code toolName}, as an AssertJ
     * {@link ListAssert} so it composes with any list matcher (AssertJ's own "extracting" idiom —
     * hands off to a different assert type rather than staying on {@code this}). Calls to the tool
     * whose parsed arguments don't include {@code argKey} are excluded.
     */
    public ListAssert<Object> extractingToolArgument(String toolName, String argKey) {
        isNotNull();
        List<Object> values =
                actual.getSteps().stream()
                        .filter(
                                step ->
                                        step.getAction() != null
                                                && step.getAction().equalsIgnoreCase(toolName))
                        .map(step -> parseArgs(step.getActionInput()).get(argKey))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
        return assertThat(values)
                .as("values of argument <%s> across calls to tool <%s>", argKey, toolName);
    }

    /** Passes if any call to {@code toolName} had {@code argKey} equal to {@code expectedValue}. */
    public AgentResultAssert usesToolWithArgument(
            String toolName, String argKey, Object expectedValue) {
        EvalChecks.check(
                M_USESTOOLWITHARGUMENT,
                () -> {
                    isNotNull();
                    List<AgentResult.AgentStep> callsToTool =
                            actual.getSteps().stream()
                                    .filter(
                                            step ->
                                                    step.getAction() != null
                                                            && step.getAction()
                                                                    .equalsIgnoreCase(toolName))
                                    .collect(Collectors.toList());
                    boolean found =
                            callsToTool.stream()
                                    .anyMatch(
                                            step ->
                                                    Objects.equals(
                                                            parseArgs(step.getActionInput())
                                                                    .get(argKey),
                                                            expectedValue));
                    if (!found) {
                        List<String> rawArgs =
                                callsToTool.stream()
                                        .map(AgentResult.AgentStep::getActionInput)
                                        .collect(Collectors.toList());
                        failWithMessage(
                                "Expected a call to tool <%s> with argument <%s>=<%s> but found none. Calls to %s: %s",
                                toolName, argKey, expectedValue, toolName, rawArgs);
                    }
                });
        return this;
    }

    /**
     * Passes if any call to {@code toolName} had a {@code argKey} argument that contains {@code
     * expectedText}, ignoring case. Meant for free-text arguments such as a search query ("the
     * agent searched for the term it was asked to verify").
     */
    public AgentResultAssert usesToolWithArgumentContaining(
            String toolName, String argKey, String expectedText) {
        EvalChecks.check(
                M_USESTOOLWITHARGUMENT,
                () -> {
                    isNotNull();
                    List<String> values =
                            actual.getSteps().stream()
                                    .filter(
                                            step ->
                                                    step.getAction() != null
                                                            && step.getAction()
                                                                    .equalsIgnoreCase(toolName))
                                    .map(step -> parseArgs(step.getActionInput()).get(argKey))
                                    .filter(Objects::nonNull)
                                    .map(String::valueOf)
                                    .collect(Collectors.toList());
                    String needle = expectedText.toLowerCase(java.util.Locale.ROOT);
                    if (values.stream()
                            .noneMatch(
                                    v -> v.toLowerCase(java.util.Locale.ROOT).contains(needle))) {
                        failWithMessage(
                                "Expected a call to tool <%s> whose <%s> contains <%s> but found: %s",
                                toolName, argKey, expectedText, values);
                    }
                });
        return this;
    }

    /** Reads {@code AgentResult.getUsage().getTotalTokens()} across the whole run. */
    public AgentResultAssert usesFewerTokensThan(int maxTotalTokens) {
        EvalChecks.checkMeasured(
                M_TOKENS,
                actual == null ? null : (double) (actual.getUsage().getTotalTokens()),
                "tokens",
                (double) maxTotalTokens,
                () -> {
                    isNotNull();
                    int actualTokens = actual.getUsage().getTotalTokens();
                    if (actualTokens >= maxTotalTokens) {
                        failWithMessage(
                                "Expected agent run to use fewer than <%s> total tokens but used <%s>",
                                maxTotalTokens, actualTokens);
                    }
                });
        return this;
    }

    /** Flags looping/dithering: repeated identical action+input pairs within a single run. */
    public AgentResultAssert hasRedundantActionCountAtMost(int max) {
        EvalChecks.checkMeasured(
                M_REDUNDANT,
                actual == null ? null : (double) (actual.getRedundantActionCount()),
                "actions",
                (double) max,
                () -> {
                    isNotNull();
                    int actualCount = actual.getRedundantActionCount();
                    if (actualCount > max) {
                        failWithMessage(
                                "Expected at most <%s> redundant (repeated) actions but had <%s>",
                                max, actualCount);
                    }
                });
        return this;
    }

    public AgentResultAssert hasValidJson(Class<?> shape) {
        EvalChecks.check(
                M_HASVALIDJSON,
                () -> {
                    isNotNull();
                    try {
                        new ObjectMapper().readValue(actual.getFinalAnswer(), shape);
                    } catch (Exception e) {
                        failWithMessage(
                                "Expected final answer to be valid JSON matching <%s> but parsing failed: %s",
                                shape.getSimpleName(), e.getMessage());
                    }
                });
        return this;
    }

    /**
     * Parses a step's raw action-input JSON into a Map. Falls back to {@code {"input": rawText}}
     * for a non-JSON payload — the same convention {@code ReActAgent} itself uses when handing
     * arguments to a tool, so an argument key of {@code "input"} still resolves consistently.
     */
    private static Map<String, Object> parseArgs(String actionInput) {
        if (actionInput == null || actionInput.isBlank()) {
            return Map.of();
        }
        try {
            return new ObjectMapper()
                    .readValue(actionInput, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of("input", actionInput);
        }
    }

    private List<String> toolNames() {
        return actual.getSteps().stream()
                .map(AgentResult.AgentStep::getAction)
                .collect(Collectors.toList());
    }

    private static boolean allEqualIgnoringCase(List<String> a, List<String> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equalsIgnoreCase(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSubsequenceIgnoringCase(
            List<String> expectedSubsequence, List<String> actual) {
        int actualIndex = 0;
        for (String expectedTool : expectedSubsequence) {
            boolean found = false;
            while (actualIndex < actual.size()) {
                boolean isMatch = actual.get(actualIndex).equalsIgnoreCase(expectedTool);
                actualIndex++;
                if (isMatch) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
