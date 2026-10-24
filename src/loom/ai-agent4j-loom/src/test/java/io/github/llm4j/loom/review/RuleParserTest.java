package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.llm4j.loom.review.model.FixSafety;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.rule.Condition;
import io.github.llm4j.loom.review.rule.Op;
import io.github.llm4j.loom.review.rule.Operand;
import io.github.llm4j.loom.review.rule.RuleDoc;
import io.github.llm4j.loom.review.rule.RuleParser;
import org.junit.jupiter.api.Test;

/**
 * R58, R61, R62 of spec 09: a rule is a document over a closed vocabulary, and every shape error is
 * reported with its place.
 */
class RuleParserTest {

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    static RuleParser.Parsed parse(String yaml) throws Exception {
        return RuleParser.parse(YAML.readTree(yaml), "rules.yaml");
    }

    static final String MINIMAL =
            """
            id: LR01
            lens: reliability
            severity: medium
            about: step
            when: { field: retry, is: false }
            title: "t"
            fixHint: "h"
            examples: { fires: [a.loom], quiet: [b.loom] }
            """;

    @Test
    void aMinimalRuleParsesWithItsDefaults() throws Exception {
        RuleParser.Parsed p = parse(MINIMAL);
        assertThat(p.problems()).isEmpty();
        RuleDoc r = p.rules().get(0);
        assertThat(r.id()).isEqualTo("LR01");
        assertThat(r.lens()).isEqualTo(Lens.RELIABILITY);
        assertThat(r.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(r.why()).isEmpty();
        assertThat(r.heuristic()).isFalse();
        assertThat(r.fires()).hasSize(1);
        assertThat(r.when()).isInstanceOf(Condition.Leaf.class);
    }

    @Test
    void aFileMayHoldOneRuleOrAList() throws Exception {
        assertThat(parse("- " + MINIMAL.replace("\n", "\n  ").stripTrailing()).rules()).hasSize(1);
        assertThat(parse(MINIMAL).rules()).hasSize(1);
    }

    @Test
    void everyCombinatorAndOperatorOfTheVocabularyParses() throws Exception {
        String yaml =
                MINIMAL.replace(
                        "when: { field: retry, is: false }",
                        """
                        when:
                          all:
                            - { field: kind, in: [delegate, loop] }
                            - any:
                                - { field: retryCount, gte: 2 }
                                - not: { field: label, startsWith: "x" }
                            - { field: label, contains: "a" }
                            - { field: label, matches: "^a.*" }
                            - { field: agent.tools, count: { gt: 0 } }
                            - { field: agent.tools, some: { field: reach, is: sends } }
                            - { field: label, exists: true }
                            - { field: label, missing: true }
                            - { field: line, lt: { setting: limit } }
                            - { field: label, isNot: { field: kind } }
                        """);
        RuleParser.Parsed p = parse(yaml);
        assertThat(p.problems()).isEmpty();
        Condition.All all = (Condition.All) p.rules().get(0).when();
        assertThat(all.all()).hasSize(10);
        Condition.Leaf in = (Condition.Leaf) all.all().get(0);
        assertThat(in.op()).isEqualTo(Op.IN);
        assertThat(in.operand()).isInstanceOf(Operand.Literals.class);
        Condition.Leaf some = (Condition.Leaf) all.all().get(5);
        assertThat(some.nested()).isNotNull();
        assertThat(((Condition.Leaf) all.all().get(8)).operand())
                .isEqualTo(new Operand.Setting("limit"));
        assertThat(((Condition.Leaf) all.all().get(9)).operand())
                .isEqualTo(new Operand.Field("kind"));
    }

    @Test
    void aFixTemplateParsesWithItsSafetyAndOperations() throws Exception {
        String yaml =
                MINIMAL
                        + """
                fix:
                  safety: safe
                  label: "Add it"
                  insertAfter: { anchor: statementEnd, text: " x" }
                  deleteLines: { anchor: { lineOf: line } }
                """;
        RuleDoc r = parse(yaml).rules().get(0);
        assertThat(r.fix().safety()).isEqualTo(FixSafety.SAFE);
        assertThat(r.fix().operations())
                .extracting(o -> o.kind() + ":" + o.anchor())
                .containsExactly("insertAfter:statementEnd", "deleteLines:lineOf:line");
    }

    @Test
    void optionsParseWithTheirQuestionsAndOperations() throws Exception {
        String yaml =
                MINIMAL
                        + """
                options:
                  - id: later
                    label: "Later"
                    explain: "Does it later."
                    safety: review
                    references: true
                    ask:
                      - { name: n, prompt: "How many", default: "3" }
                    insertAfter: { anchor: statementEnd, text: " max {ask.n}" }
                """;
        RuleDoc r = parse(yaml).rules().get(0);
        assertThat(r.options()).hasSize(1);
        var o = r.options().get(0);
        assertThat(o.id()).isEqualTo("later");
        assertThat(o.safety()).isEqualTo(FixSafety.REVIEW);
        assertThat(o.showReferences()).isTrue();
        assertThat(o.asks())
                .extracting(a -> a.name() + "=" + a.defaultValue())
                .containsExactly("n=3");
        assertThat(o.operations()).extracting(op -> op.kind()).containsExactly("insertAfter");
    }

    @Test
    void optionMistakesAreReportedWithTheirPlace() throws Exception {
        String twice =
                MINIMAL
                        + """
                options:
                  - { id: a, label: "A", insertAfter: { anchor: statementEnd, text: "x" } }
                  - { id: a, label: "B", insertAfter: { anchor: statementEnd, text: "y" } }
                  - { id: c, label: "C" }
                  - { id: D, label: "D", insertAfter: { anchor: statementEnd, text: "z" } }
                """;
        assertThat(parse(twice).problems())
                .anyMatch(p -> p.contains("'a' is used twice"))
                .anyMatch(p -> p.contains("needs at least one operation"))
                .anyMatch(p -> p.contains(".id: a short lowercase name"));
    }

    @Test
    void examplesMayBeFilesOrInlineSnippetsWithAnExpectedCount() throws Exception {
        String yaml =
                MINIMAL.replace(
                        "examples: { fires: [a.loom], quiet: [b.loom] }",
                        "examples:\n  fires:\n    - { inline: \"agent A {}\", expect: 2 }\n    - file: x.loom\n  quiet: [q.loom]\n");
        RuleDoc r = parse(yaml).rules().get(0);
        assertThat(r.fires().get(0).inline()).isEqualTo("agent A {}");
        assertThat(r.fires().get(0).expect()).isEqualTo(2);
        assertThat(r.fires().get(1).file()).isEqualTo("x.loom");
    }

    @Test
    void missingRequiredKeysAreAllReportedWithTheRuleThatLacksThem() throws Exception {
        RuleParser.Parsed p = parse("id: LX01\nwhen: { field: a, is: true }\n");
        assertThat(p.rules()).isEmpty();
        assertThat(p.problems())
                .anyMatch(s -> s.startsWith("LX01") && s.contains("needs a lens"))
                .anyMatch(s -> s.contains("needs a severity"))
                .anyMatch(s -> s.contains("needs 'about'"))
                .anyMatch(s -> s.contains("needs a title"))
                .anyMatch(s -> s.contains("fixHint"));
    }

    @Test
    void anUnknownKeyIsRefusedWithTheNearestKey() throws Exception {
        RuleParser.Parsed p = parse(MINIMAL + "severty: low\n");
        assertThat(p.problems())
                .anyMatch(
                        s ->
                                s.contains("unknown key 'severty'")
                                        && s.contains("did you mean 'severity'"));
    }

    @Test
    void anUnknownOperatorOrTwoOperatorsOrNoOperatorIsRefused() throws Exception {
        assertThat(parse(MINIMAL.replace("is: false", "equals: false")).problems())
                .anyMatch(s -> s.contains("'equals' is not an operator"));
        assertThat(parse(MINIMAL.replace("is: false", "is: false, isNot: true")).problems())
                .anyMatch(s -> s.contains("one operator per condition"));
        assertThat(
                        parse(MINIMAL.replace("{ field: retry, is: false }", "{ field: retry }"))
                                .problems())
                .anyMatch(s -> s.contains("needs an operator"));
        assertThat(
                        parse(MINIMAL.replace("{ field: retry, is: false }", "{ is: false }"))
                                .problems())
                .anyMatch(s -> s.contains("needs 'field'"));
    }

    @Test
    void thereIsNoWayToWriteAnExpressionOrACall() throws Exception {
        assertThat(parse(MINIMAL.replace("is: false", "is: \"a + b\"")).problems())
                .isEmpty(); // a string is just a string
        assertThat(
                        parse(MINIMAL.replace("{ field: retry, is: false }", "\"retry == false\""))
                                .problems())
                .anyMatch(s -> s.contains("a condition is a map"));
        assertThat(parse(MINIMAL.replace("is: false", "is: { call: x }")).problems())
                .anyMatch(s -> s.contains("a reference is { setting: key } or { field: path }"));
        assertThat(parse(MINIMAL.replace("is: false", "is: [1, 2]")).problems())
                .anyMatch(s -> s.contains("expected a string, a number or true or false"));
    }

    @Test
    void badValuesAreRefusedWithTheirPlace() throws Exception {
        assertThat(parse(MINIMAL.replace("is: false", "exists: false")).problems())
                .anyMatch(s -> s.contains("write exists: true"));
        assertThat(parse(MINIMAL.replace("is: false", "count: { foo: 1 }")).problems())
                .anyMatch(s -> s.contains("use gt, gte, lt, lte, is or isNot"));
        assertThat(parse(MINIMAL.replace("is: false", "in: []")).problems())
                .anyMatch(s -> s.contains("non-empty list"));
        assertThat(parse(MINIMAL.replace("lens: reliability", "lens: reliabilty")).problems())
                .anyMatch(
                        s ->
                                s.contains("is not a lens")
                                        && s.contains("did you mean 'reliability'"));
        assertThat(parse(MINIMAL.replace("severity: medium", "severity: huge")).problems())
                .anyMatch(s -> s.contains("severity takes high"));
        assertThat(parse(MINIMAL.replace("all", "all")).problems()).isEmpty();
    }

    @Test
    void conditionsAreBoundedInDepthAndSize() throws Exception {
        StringBuilder deep = new StringBuilder("{ field: retry, is: false }");
        for (int i = 0; i < 8; i++) deep = new StringBuilder("{ not: " + deep + " }");
        assertThat(
                        parse(MINIMAL.replace("{ field: retry, is: false }", deep.toString()))
                                .problems())
                .anyMatch(s -> s.contains("nested more than"));
        StringBuilder wide = new StringBuilder("{ all: [");
        for (int i = 0; i < 205; i++)
            wide.append(i == 0 ? "" : ",").append("{ field: retry, is: false }");
        wide.append("] }");
        assertThat(
                        parse(MINIMAL.replace("{ field: retry, is: false }", wide.toString()))
                                .problems())
                .anyMatch(s -> s.contains("more than 200 parts"));
    }

    @Test
    void anEmptyFileAndAScalarAreReportedNotCrashed() throws Exception {
        assertThat(RuleParser.parse(YAML.readTree(""), "empty.yaml").problems())
                .anyMatch(s -> s.contains("the file is empty"));
        assertThat(parse("- just text\n").problems()).anyMatch(s -> s.contains("a rule is a map"));
    }

    @Test
    void anEscalationRaisesTheSeverityWhenItsConditionHolds() throws Exception {
        String yaml =
                "id: ACME-001\nlens: reliability\nseverity: medium\nabout: agent\nwhen: { field: hasTools, is: false }\n"
                        + "escalate:\n  - when: { field: hasSchema, is: false }\n    severity: high\n"
                        + "title: t\nfixHint: f\n";
        RuleParser.Parsed p = parse(yaml);
        assertThat(p.problems()).isEmpty();
        var rule = p.rules().get(0);
        assertThat(rule.escalations()).hasSize(1);
        assertThat(rule.severityFor(c -> true))
                .isEqualTo(io.github.llm4j.loom.review.model.Severity.HIGH);
        assertThat(rule.severityFor(c -> false))
                .isEqualTo(io.github.llm4j.loom.review.model.Severity.MEDIUM);
    }

    @Test
    void aBadEscalationIsRefusedWithItsPlace() throws Exception {
        String yaml =
                "id: ACME-001\nlens: reliability\nseverity: medium\nabout: agent\nwhen: { field: hasTools, is: false }\n"
                        + "escalate:\n  - when: { field: hasSchema, is: false }\n    severity: huge\n"
                        + "title: t\nfixHint: f\n";
        assertThat(parse(yaml).problems()).anyMatch(s -> s.contains("escalate[0].severity"));
    }
}
