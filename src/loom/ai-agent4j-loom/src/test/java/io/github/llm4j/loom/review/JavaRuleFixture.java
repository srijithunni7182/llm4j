package io.github.llm4j.loom.review;

import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.engine.ReviewRule;
import io.github.llm4j.loom.review.engine.RuleInfo;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Location;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.util.List;
import java.util.Optional;

/** A Java rule as an organisation would write one: "no agent may be called Intern". */
public final class JavaRuleFixture implements ReviewRule {

    public static final RuleInfo INFO =
            new RuleInfo(
                    "ORG-001",
                    Lens.GOVERNANCE,
                    Severity.MEDIUM,
                    "No agent may be called Intern",
                    "Our policy forbids the name. What to do: rename the agent.",
                    "java org-rules");

    @Override
    public String name() {
        return "org-rules";
    }

    @Override
    public List<RuleInfo> rules() {
        return List.of(INFO);
    }

    @Override
    public void collect(ReviewInput input, ReviewSettings settings, Sink sink) {
        input.script().getAgents().stream()
                .filter(a -> a.getName().equals("Intern"))
                .forEach(
                        a ->
                                sink.finding(
                                        new Finding(
                                                "ORG-001",
                                                Lens.GOVERNANCE,
                                                Severity.MEDIUM,
                                                Location.at(input.file(), a.getLine()),
                                                "agent "
                                                        + a.getName()
                                                        + " breaks the naming policy",
                                                "Policy.",
                                                "Rename the agent.",
                                                List.of(),
                                                Optional.empty(),
                                                List.of(),
                                                "java org-rules")));
    }
}
