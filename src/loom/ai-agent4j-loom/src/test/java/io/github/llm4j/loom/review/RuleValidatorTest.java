package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.llm4j.loom.review.fact.FactModel;
import io.github.llm4j.loom.review.rule.RuleDoc;
import io.github.llm4j.loom.review.rule.RuleParser;
import io.github.llm4j.loom.review.rule.RuleValidator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * R66 of spec 09: a rule that cannot work is refused when it loads, with the nearest fact named.
 */
class RuleValidatorTest {

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    static List<String> problems(String about, String when, String extra) throws Exception {
        String doc =
                "id: LT01\nlens: reliability\nseverity: low\nabout: "
                        + about
                        + "\ntitle: \"t\"\nfixHint: \"h\"\nexamples: { fires: [a], quiet: [b] }\nwhen: "
                        + when
                        + "\n"
                        + extra;
        RuleParser.Parsed p = RuleParser.parse(YAML.readTree(doc), "t");
        assertThat(p.problems()).as("parse").isEmpty();
        return RuleValidator.validate(p.rules().get(0), FactModel.v1());
    }

    static List<String> problems(String when) throws Exception {
        return problems("step", when, "");
    }

    @Test
    void aSoundRuleHasNoProblems() throws Exception {
        assertThat(
                        problems(
                                "{ all: [ { field: kind, is: delegate }, { field: agent.hasTools, is: true }, { field: retryCount, gte: 1 } ] }"))
                .isEmpty();
    }

    @Test
    void anUnknownEntityIsRefusedWithTheKindsThereAre() throws Exception {
        assertThat(problems("stp", "{ field: kind, is: x }", ""))
                .anyMatch(
                        s ->
                                s.contains("'stp' is not an entity")
                                        && s.contains("did you mean 'step'")
                                        && s.contains("agent"));
    }

    @Test
    void anUnknownFactIsRefusedWithTheNearestOne() throws Exception {
        assertThat(problems("{ field: agent.hasTols, is: true }"))
                .anyMatch(
                        s ->
                                s.contains("'hasTols' is not a fact of agent")
                                        && s.contains("did you mean 'hasTools'"));
        assertThat(problems("{ field: retry.count, is: 1 }"))
                .anyMatch(s -> s.contains("not a reference"));
        assertThat(problems("{ field: a.b.c.d, is: 1 }"))
                .anyMatch(s -> s.contains("more than three"));
    }

    @Test
    void anOperatorThatDoesNotFitTheTypeOfTheFactIsRefused() throws Exception {
        assertThat(problems("{ field: kind, gt: 3 }"))
                .anyMatch(s -> s.contains("gt needs a number"));
        assertThat(problems("{ field: retry, startsWith: x }"))
                .anyMatch(s -> s.contains("startsWith needs text"));
        assertThat(problems("{ field: retry, matches: x }"))
                .anyMatch(s -> s.contains("matches needs text"));
        assertThat(problems("{ field: kind, count: { gt: 0 } }"))
                .anyMatch(s -> s.contains("count needs a list"));
        assertThat(problems("{ field: kind, some: { field: x, is: 1 } }"))
                .anyMatch(s -> s.contains("needs a list of entities"));
        assertThat(problems("{ field: agent, is: x }"))
                .anyMatch(s -> s.contains("compares one value"));
        assertThat(problems("{ field: retry, contains: x }"))
                .anyMatch(s -> s.contains("contains needs text or a list"));
    }

    @Test
    void valuesMustBeOfTheRightKindAndEnumWordsMustExist() throws Exception {
        assertThat(problems("agent", "{ field: guardPii, is: loud }", ""))
                .anyMatch(
                        s -> s.contains("'loud' is not a value of guardPii") && s.contains("mask"));
        assertThat(problems("agent", "{ field: guardPii, in: [warn, shout] }", ""))
                .anyMatch(s -> s.contains("'shout' is not a value"));
        assertThat(problems("{ field: retry, is: \"yes\" }"))
                .anyMatch(s -> s.contains("retry is true or false"));
        assertThat(problems("{ field: retryCount, is: \"two\" }"))
                .anyMatch(s -> s.contains("retryCount is a number"));
        assertThat(problems("{ field: retryCount, gt: \"two\" }"))
                .anyMatch(s -> s.contains("compare with a number"));
        assertThat(problems("{ field: label, matches: \"([\" }"))
                .anyMatch(s -> s.contains("not a valid regular expression"));
    }

    @Test
    void referencesToOtherFactsAreCheckedToo() throws Exception {
        assertThat(problems("{ field: label, is: { field: nonsense } }"))
                .anyMatch(s -> s.contains("'nonsense' is not a fact of step"));
        assertThat(problems("{ field: label, is: { field: kind } }")).isEmpty();
        assertThat(problems("{ field: retryCount, lt: { setting: blast.max-effects } }")).isEmpty();
    }

    @Test
    void nestedConditionsInAQuantifierAreCheckedAgainstTheElementKind() throws Exception {
        assertThat(problems("agent", "{ field: tools, some: { field: reach, is: sends } }", ""))
                .isEmpty();
        assertThat(problems("agent", "{ field: tools, some: { field: reach, is: loud } }", ""))
                .anyMatch(s -> s.contains("'loud' is not a value of reach"));
        assertThat(problems("agent", "{ field: tools, some: { field: hasTools, is: true } }", ""))
                .anyMatch(s -> s.contains("'hasTools' is not a fact of tool"));
    }

    @Test
    void placeholdersMustBeRealPathsOfTheRulesEntity() throws Exception {
        assertThat(
                        problems("step", "{ field: retry, is: false }", "").stream()
                                .filter(s -> s.contains("{")))
                .isEmpty();
        String doc =
                "id: LT01\nlens: reliability\nseverity: low\nabout: step\ntitle: \"{lable} has {agent.name}\"\nwhy: \"{setting.fix.retry}\"\nfixHint: \"h\"\nexamples: { fires: [a], quiet: [b] }\nwhen: { field: retry, is: false }\n";
        RuleDoc r = RuleParser.parse(YAML.readTree(doc), "t").rules().get(0);
        assertThat(RuleValidator.validate(r, FactModel.v1()))
                .hasSize(1)
                .first()
                .asString()
                .contains("{lable}")
                .contains("did you mean 'label'");
    }

    @Test
    void requiresAndReferencesMustBeWellFormed() throws Exception {
        assertThat(problems("step", "{ field: retry, is: false }", "requires: [data.regions]"))
                .anyMatch(s -> s.contains("is not setting.<key> or graph"));
        assertThat(
                        problems(
                                "step",
                                "{ field: retry, is: false }",
                                "requires: [setting.data.regions, graph]"))
                .isEmpty();
        assertThat(problems("step", "{ field: retry, is: false }", "references: [LLM11]"))
                .anyMatch(s -> s.contains("not an OWASP LLM id"));
        assertThat(problems("step", "{ field: retry, is: false }", "references: [LLM06, LLM10]"))
                .isEmpty();
    }

    @Test
    void aRuleWithoutBothKindsOfExampleIsRefused() throws Exception {
        String doc =
                "id: LT01\nlens: reliability\nseverity: low\nabout: step\ntitle: t\nfixHint: h\nwhen: { field: retry, is: false }\nexamples: { fires: [a] }\n";
        RuleDoc r = RuleParser.parse(YAML.readTree(doc), "t").rules().get(0);
        assertThat(RuleValidator.validate(r, FactModel.v1()))
                .anyMatch(s -> s.contains("at least one script it stays quiet on"));
        String none = doc.replace("examples: { fires: [a] }\n", "");
        RuleDoc r2 = RuleParser.parse(YAML.readTree(none), "t").rules().get(0);
        assertThat(RuleValidator.validate(r2, FactModel.v1()))
                .anyMatch(s -> s.contains("fires"))
                .anyMatch(s -> s.contains("quiet"));
    }

    @Test
    void fixTemplatesAreCheckedForAnchorsAndPlaceholders() throws Exception {
        String ok =
                "fix:\n  safety: safe\n  label: l\n  insertAfter: { anchor: statementEnd, text: \" {setting.fix.retry}\" }\n";
        assertThat(problems("step", "{ field: retry, is: false }", ok)).isEmpty();
        assertThat(
                        problems(
                                "step",
                                "{ field: retry, is: false }",
                                ok.replace("statementEnd", "middle")))
                .anyMatch(s -> s.contains("'middle' is not an anchor"));
        assertThat(
                        problems(
                                "step",
                                "{ field: retry, is: false }",
                                ok.replace("statementEnd", "{ lineOf: nonsense }")))
                .anyMatch(s -> s.contains("lineOf names 'nonsense'"));
        assertThat(
                        problems(
                                "step",
                                "{ field: retry, is: false }",
                                ok.replace("{setting.fix.retry}", "{nope}")))
                .anyMatch(s -> s.contains("{nope}"));
    }
}
