package io.github.llm4j.eval.assertions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.AgentResult;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.assertj.core.api.AbstractObjectAssert;

/**
 * AssertJ custom assertion for a {@link AgentResult}, the outcome of a {@code ReActAgent} run.
 * Obtain one via {@link AgentAssertions#assertThat(AgentResult)}, not by constructing it directly.
 */
public class AgentResultAssert extends AbstractObjectAssert<AgentResultAssert, AgentResult> {

    public AgentResultAssert(AgentResult actual) {
        super(actual, AgentResultAssert.class);
    }

    public AgentResultAssert hasFinalAnswerContaining(String expectedSubstring) {
        isNotNull();
        assertThat(actual.getFinalAnswer())
                .as("final answer of agent result")
                .containsIgnoringCase(expectedSubstring);
        return this;
    }

    public AgentResultAssert hasFinalAnswerMatching(Pattern pattern) {
        isNotNull();
        String finalAnswer = actual.getFinalAnswer();
        if (finalAnswer == null || !pattern.matcher(finalAnswer).find()) {
            failWithMessage(
                    "Expected final answer to match pattern <%s> but was <%s>", pattern, finalAnswer);
        }
        return this;
    }

    public AgentResultAssert usesTool(String toolName) {
        isNotNull();
        boolean used = toolNames().stream().anyMatch(name -> name.equalsIgnoreCase(toolName));
        if (!used) {
            failWithMessage(
                    "Expected agent to use tool <%s> but it used: %s", toolName, toolNames());
        }
        return this;
    }

    public AgentResultAssert usesToolsExactly(String... toolNamesInOrder) {
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
        return this;
    }

    /**
     * Passes if the given tools were used in that relative order, allowing other tool calls to
     * appear in between — an ordered-subsequence check, unlike the exact/contiguous {@link
     * #usesToolsExactly(String...)}. Useful for trajectory checks where the agent is allowed to
     * take extra steps as long as the important ones happen in the right order.
     */
    public AgentResultAssert usesToolsInOrder(String... toolNamesInOrder) {
        isNotNull();
        List<String> actualToolNames = toolNames();
        List<String> expectedSubsequence = List.of(toolNamesInOrder);
        if (!isSubsequenceIgnoringCase(expectedSubsequence, actualToolNames)) {
            failWithMessage(
                    "Expected agent to use tools %s in that relative order but it used: %s",
                    expectedSubsequence, actualToolNames);
        }
        return this;
    }

    public AgentResultAssert usesNoTools() {
        isNotNull();
        List<String> actualToolNames = toolNames();
        if (!actualToolNames.isEmpty()) {
            failWithMessage("Expected agent to use no tools but it used: %s", actualToolNames);
        }
        return this;
    }

    public AgentResultAssert isConfidentAbove(double threshold) {
        isNotNull();
        double score = actual.getConfidence() == null ? 0.0 : actual.getConfidence().getScore();
        if (score <= threshold) {
            failWithMessage(
                    "Expected agent confidence to be above <%s> but was <%s>", threshold, score);
        }
        return this;
    }

    public AgentResultAssert completedSuccessfully() {
        isNotNull();
        if (!actual.isCompleted()) {
            failWithMessage(
                    "Expected agent to complete successfully but it did not (uncertaintyReason=%s)",
                    actual.getUncertaintyReason());
        }
        return this;
    }

    public AgentResultAssert completesWithinIterations(int maxIterations) {
        isNotNull();
        if (actual.getIterations() > maxIterations) {
            failWithMessage(
                    "Expected agent to complete within <%s> iterations but it took <%s>",
                    maxIterations, actual.getIterations());
        }
        return this;
    }

    public AgentResultAssert hasValidJson(Class<?> shape) {
        isNotNull();
        try {
            new ObjectMapper().readValue(actual.getFinalAnswer(), shape);
        } catch (Exception e) {
            failWithMessage(
                    "Expected final answer to be valid JSON matching <%s> but parsing failed: %s",
                    shape.getSimpleName(), e.getMessage());
        }
        return this;
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

    private static boolean isSubsequenceIgnoringCase(List<String> expectedSubsequence, List<String> actual) {
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
