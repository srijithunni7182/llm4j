package io.github.llm4j.eval.assertions;

import io.github.llm4j.eval.export.EvalChecks;
import io.github.llm4j.eval.export.MetricRef;
import io.github.llm4j.eval.export.WorkflowTrace;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.assertj.core.api.AbstractObjectAssert;

/**
 * Deterministic trajectory checks over a {@link WorkflowTrace}: free to run, on every build. Each
 * records one {@code ASSERTION} evaluation in the {@code workflows} family and throws like AssertJ
 * when it fails.
 */
public class WorkflowTraceAssert extends AbstractObjectAssert<WorkflowTraceAssert, WorkflowTrace> {

    private static MetricRef m(String id, String name, String facet, String dim) {
        return MetricRef.assertion(id, name, "workflows", facet, dim);
    }

    private static final MetricRef M_ORDER =
            m(
                    "tool-order-matches-expected",
                    "Tool order matches expected",
                    "trajectory",
                    "orchestration");
    private static final MetricRef M_AGENTS =
            m("required-agents-invoked", "Required agents invoked", "trajectory", "orchestration");
    private static final MetricRef M_ALLOWED =
            m(
                    "no-unexpected-tool-calls",
                    "No unexpected tool calls",
                    "trajectory",
                    "orchestration");
    private static final MetricRef M_PATH =
            m("follows-expected-path", "Follows expected path", "trajectory", "orchestration");
    private static final MetricRef M_SPEND =
            MetricRef.measured(
                    "spend-within-budget",
                    "Spend within budget",
                    "workflows",
                    "trajectory",
                    "efficiency",
                    "usd");
    private static final MetricRef M_BRANCH =
            m("correct-branch-taken", "Correct branch taken", "orchestration", "orchestration");
    private static final MetricRef M_LOOP =
            m("loop-within-bound", "Loop within bound", "orchestration", "orchestration");
    private static final MetricRef M_REWIND =
            m("rewinds-within-cap", "Rewinds within cap", "orchestration", "orchestration");
    private static final MetricRef M_APPROVAL =
            m(
                    "approval-requested-when-required",
                    "Approval requested when required",
                    "orchestration",
                    "orchestration");
    private static final MetricRef M_OUTPUT =
            m("typed-output-complete", "Typed output complete", "outputs", "correctness");
    private static final MetricRef M_GUARD =
            m("pii-guard-held", "Guard held", "guardrails", "safety");
    private static final MetricRef M_SECRETS =
            m("no-secret-in-trace", "No secret in trace", "guardrails", "safety");

    private static final Pattern CREDENTIAL =
            Pattern.compile(
                    "(?i)(sk-[a-z0-9]{16,}|AKIA[0-9A-Z]{16}|bearer\\s+[a-z0-9._\\-]{20,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|(?:password|secret|api[_-]?key|token)\\s*[=:]\\s*\\S{6,})");

    public WorkflowTraceAssert(WorkflowTrace actual) {
        super(actual, WorkflowTraceAssert.class);
    }

    /**
     * Passes if the tool calls (events of type {@code tool}) include {@code tools} in that relative
     * order.
     */
    public WorkflowTraceAssert usesToolsInOrder(String... tools) {
        EvalChecks.check(
                M_ORDER,
                () -> {
                    isNotNull();
                    List<String> used = toolNames();
                    if (!subsequence(List.of(tools), used)) {
                        failWithMessage(
                                "Expected tools %s in that relative order but the workflow used: %s",
                                List.of(tools), used);
                    }
                });
        return this;
    }

    public WorkflowTraceAssert usesToolsExactly(String... tools) {
        EvalChecks.check(
                M_ORDER,
                () -> {
                    isNotNull();
                    List<String> used = toolNames();
                    if (!used.equals(List.of(tools))) {
                        failWithMessage(
                                "Expected exactly the tools %s but the workflow used: %s",
                                List.of(tools), used);
                    }
                });
        return this;
    }

    /** Passes if these agents were delegated to, in this relative order. */
    public WorkflowTraceAssert visitsInOrder(String... agents) {
        EvalChecks.check(
                M_AGENTS,
                () -> {
                    isNotNull();
                    if (!subsequence(List.of(agents), actual.agentsInOrder())) {
                        failWithMessage(
                                "Expected agents %s in that relative order but the workflow invoked: %s",
                                List.of(agents), actual.agentsInOrder());
                    }
                });
        return this;
    }

    /**
     * Passes if {@code agent} was delegated to exactly {@code times} times (for example once per
     * round).
     */
    public WorkflowTraceAssert delegatesToTimes(String agent, int times) {
        EvalChecks.check(
                M_AGENTS,
                () -> {
                    isNotNull();
                    long n = actual.delegationsTo(agent);
                    if (n != times) {
                        failWithMessage(
                                "Expected %s to be delegated to %s time(s) but it was %s time(s); counts: %s",
                                agent, times, n, actual.delegationCounts());
                    }
                });
        return this;
    }

    /** Passes if each of these agents was invoked at least once, in any order. */
    public WorkflowTraceAssert invokesAgents(String... agents) {
        EvalChecks.check(
                M_AGENTS,
                () -> {
                    isNotNull();
                    List<String> missing = new ArrayList<>(List.of(agents));
                    missing.removeAll(actual.agentsInOrder());
                    if (!missing.isEmpty()) {
                        failWithMessage(
                                "Expected agents %s to be invoked but %s were not",
                                List.of(agents), missing);
                    }
                });
        return this;
    }

    public WorkflowTraceAssert callsOnlyAllowedTools(Set<String> allowed) {
        EvalChecks.check(
                M_ALLOWED,
                () -> {
                    isNotNull();
                    List<String> unexpected = new ArrayList<>();
                    for (String t : toolNames()) {
                        if (!allowed.contains(t)) {
                            unexpected.add(t);
                        }
                    }
                    if (!unexpected.isEmpty()) {
                        failWithMessage(
                                "Expected only tools %s but the workflow also called: %s",
                                allowed, unexpected);
                    }
                });
        return this;
    }

    /** Passes if the nodes visited are exactly the expected path. */
    public WorkflowTraceAssert followsExpectedPath() {
        EvalChecks.check(
                M_PATH,
                () -> {
                    isNotNull();
                    if (actual.expectedPath().isEmpty()) {
                        failWithMessage("No expected path was declared for this workflow trace");
                    }
                    if (!actual.actualPath().equals(actual.expectedPath())) {
                        failWithMessage(
                                "Expected the workflow to follow %s but it took %s",
                                actual.expectedPath(), actual.actualPath());
                    }
                });
        return this;
    }

    public WorkflowTraceAssert staysWithinSpend(double maxUsd) {
        EvalChecks.checkMeasured(
                M_SPEND,
                actual == null ? null : actual.totalCostUsd(),
                "usd",
                maxUsd,
                () -> {
                    isNotNull();
                    if (actual.totalCostUsd() > maxUsd) {
                        failWithMessage(
                                "Expected the workflow to spend at most $%s but it spent $%s",
                                maxUsd, actual.totalCostUsd());
                    }
                });
        return this;
    }

    /** Passes if a {@code decision} event at {@code node} chose {@code label}. */
    public WorkflowTraceAssert takesBranch(String node, String label) {
        EvalChecks.check(
                M_BRANCH,
                () -> {
                    isNotNull();
                    boolean ok =
                            actual.events().stream()
                                    .anyMatch(
                                            e ->
                                                    "decision".equals(e.type())
                                                            && node.equals(e.node())
                                                            && label.equalsIgnoreCase(e.text()));
                    if (!ok) {
                        failWithMessage(
                                "Expected node <%s> to take branch <%s> but decisions there were: %s",
                                node,
                                label,
                                actual.events().stream()
                                        .filter(
                                                e ->
                                                        "decision".equals(e.type())
                                                                && node.equals(e.node()))
                                        .map(WorkflowTrace.Event::text)
                                        .toList());
                    }
                });
        return this;
    }

    /** Passes if the loop node was visited at most {@code n} times. */
    public WorkflowTraceAssert loopStopsWithin(String node, int n) {
        EvalChecks.check(
                M_LOOP,
                () -> {
                    isNotNull();
                    long visits = actual.actualPath().stream().filter(node::equals).count();
                    if (visits > n) {
                        failWithMessage(
                                "Expected loop <%s> to stop within %s iterations but it ran %s",
                                node, n, visits);
                    }
                });
        return this;
    }

    public WorkflowTraceAssert rewindsAtMost(int n) {
        EvalChecks.check(
                M_REWIND,
                () -> {
                    isNotNull();
                    if (actual.rewinds() > n) {
                        failWithMessage(
                                "Expected at most %s rewinds but there were %s",
                                n, actual.rewinds());
                    }
                });
        return this;
    }

    /** Passes if an {@code approval} event came before the first visit of {@code node}. */
    public WorkflowTraceAssert requestsApprovalBefore(String node) {
        EvalChecks.check(
                M_APPROVAL,
                () -> {
                    isNotNull();
                    int approval = -1;
                    int visit = -1;
                    for (int i = 0; i < actual.events().size(); i++) {
                        WorkflowTrace.Event e = actual.events().get(i);
                        if (approval < 0 && "approval".equals(e.type())) {
                            approval = i;
                        }
                        if (visit < 0 && node.equals(e.node())) {
                            visit = i;
                        }
                    }
                    if (visit >= 0 && (approval < 0 || approval > visit)) {
                        failWithMessage(
                                "Expected an approval request before node <%s> but there was none",
                                node);
                    }
                });
        return this;
    }

    /** Passes if the output map carries every required key. */
    public WorkflowTraceAssert outputMatchesSchema(
            java.util.Map<String, ?> output, String... requiredKeys) {
        EvalChecks.check(
                M_OUTPUT,
                () -> {
                    List<String> missing = new ArrayList<>();
                    for (String k : requiredKeys) {
                        if (output == null || output.get(k) == null) {
                            missing.add(k);
                        }
                    }
                    if (!missing.isEmpty()) {
                        failWithMessage(
                                "Expected the typed output to carry %s but %s were missing",
                                List.of(requiredKeys), missing);
                    }
                });
        return this;
    }

    /**
     * Passes if a {@code guard} event for {@code guard} was recorded and none reported a breach.
     */
    public WorkflowTraceAssert guardHeld(String guard) {
        EvalChecks.check(
                M_GUARD,
                () -> {
                    isNotNull();
                    String g = guard.toLowerCase(Locale.ROOT);
                    boolean seen = false;
                    for (WorkflowTrace.Event e : actual.events()) {
                        if ("guard".equals(e.type())
                                && e.text() != null
                                && e.text().toLowerCase(Locale.ROOT).contains(g)) {
                            seen = true;
                            String blob = String.valueOf(e.data()).toLowerCase(Locale.ROOT);
                            if (blob.contains("violat")
                                    || blob.contains("breach")
                                    || blob.contains("blocked=false")) {
                                failWithMessage(
                                        "Guard <%s> reported a breach at step %s", guard, e.step());
                            }
                        }
                    }
                    if (!seen) {
                        failWithMessage("No event shows guard <%s> being applied", guard);
                    }
                });
        return this;
    }

    /**
     * Passes if no event or spend text contains something shaped like a credential. Never prints
     * the secret.
     */
    public WorkflowTraceAssert noSecretsInTrace() {
        EvalChecks.check(
                M_SECRETS,
                () -> {
                    isNotNull();
                    for (WorkflowTrace.Event e : actual.events()) {
                        if (looksSecret(e.text()) || looksSecret(String.valueOf(e.data()))) {
                            failWithMessage(
                                    "A credential-shaped value appears in the trace at step <%s> (type %s); its value is not shown",
                                    e.step(), e.type());
                        }
                    }
                });
        return this;
    }

    private static boolean looksSecret(String s) {
        return s != null && CREDENTIAL.matcher(s).find();
    }

    private List<String> toolNames() {
        List<String> out = new ArrayList<>();
        for (WorkflowTrace.Event e : actual.events()) {
            if ("tool".equals(e.type()) || "action".equals(e.type())) {
                String name =
                        e.data() != null && e.data().get("tool") != null
                                ? String.valueOf(e.data().get("tool"))
                                : e.text();
                if (name != null) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    private static boolean subsequence(List<String> wanted, List<String> actual) {
        int i = 0;
        for (String a : actual) {
            if (i < wanted.size() && a.equalsIgnoreCase(wanted.get(i))) {
                i++;
            }
        }
        return i == wanted.size();
    }
}
