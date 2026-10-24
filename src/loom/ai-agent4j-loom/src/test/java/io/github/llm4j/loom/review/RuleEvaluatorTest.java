package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.llm4j.loom.review.fact.Entity;
import io.github.llm4j.loom.review.fact.FactBase;
import io.github.llm4j.loom.review.fact.FactBuilder;
import io.github.llm4j.loom.review.rule.Condition;
import io.github.llm4j.loom.review.rule.Evaluation;
import io.github.llm4j.loom.review.rule.Evaluator;
import io.github.llm4j.loom.review.rule.RuleDoc;
import io.github.llm4j.loom.review.rule.RuleParser;
import io.github.llm4j.loom.review.rule.Template;
import io.github.llm4j.loom.review.rule.Tri;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R58, R60, R62 of spec 09: three-valued evaluation over the facts, bounded and explainable. */
class RuleEvaluatorTest {

    @TempDir Path dir;

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    static final String SCRIPT =
            """
            tool Hook { use: webhook  url: env.HOOK }
            agent Replier { model: "claude-haiku-5-5"  system: "s"  tools: [Hook, web_search]  approve: [Hook]  guard { pii: warn } }
            agent Plain { model: "m"  system: "s" }
            workflow Main(q) {
                delegate "{q}" to Replier -> reply retry 2 timeout 10s
                delegate "{q}" to Plain -> other
                note "{reply}{other}"
            }
            """;

    FactBase facts;
    Entity replier;
    Entity plain;
    Entity firstStep;

    @BeforeEach
    void setUp() throws Exception {

        facts =
                FactBuilder.standard()
                        .build(
                                ReviewFixtures.input(dir, SCRIPT, java.util.Map.of(), true),
                                ReviewSettings.parse(
                                        "p", "data.regions = eu, us\nblast.limit = 3\n"));
        replier = facts.find("agent:Replier").orElseThrow();
        plain = facts.find("agent:Plain").orElseThrow();
        firstStep =
                facts.entities("step").stream()
                        .filter(s -> s.get("kind").equals("delegate"))
                        .findFirst()
                        .orElseThrow();
    }

    static Condition when(String yaml) throws Exception {
        String doc =
                "id: LT01\nlens: reliability\nseverity: low\nabout: agent\ntitle: t\nfixHint: h\nexamples: { fires: [a], quiet: [b] }\nwhen: "
                        + yaml
                        + "\n";
        RuleParser.Parsed p = RuleParser.parse(YAML.readTree(doc), "t");
        assertThat(p.problems()).isEmpty();
        RuleDoc r = p.rules().get(0);
        return r.when();
    }

    Tri eval(String yaml, Entity e) throws Exception {
        return Evaluator.evaluate(when(yaml), e, facts).result();
    }

    @Test
    void equalityAndSetMembershipOnEveryKindOfFact() throws Exception {
        assertThat(eval("{ field: hasTools, is: true }", replier)).isEqualTo(Tri.TRUE);
        assertThat(eval("{ field: hasTools, is: true }", plain)).isEqualTo(Tri.FALSE);
        assertThat(eval("{ field: guardPii, is: warn }", replier)).isEqualTo(Tri.TRUE);
        assertThat(eval("{ field: guardPii, isNot: warn }", replier)).isEqualTo(Tri.FALSE);
        assertThat(eval("{ field: maxReach, in: [writes, sends] }", replier)).isEqualTo(Tri.TRUE);
        assertThat(eval("{ field: maxReach, notIn: [writes, sends] }", replier))
                .isEqualTo(Tri.FALSE);
        assertThat(eval("{ field: line, is: " + replier.get("line") + " }", replier))
                .isEqualTo(Tri.TRUE);
    }

    @Test
    void numbersCompareAndAgainstSettings() throws Exception {
        assertThat(eval("{ field: stepCount, gte: 1 }", replier)).isEqualTo(Tri.TRUE);
        assertThat(eval("{ field: stepCount, gt: 1 }", replier)).isEqualTo(Tri.FALSE);
        assertThat(eval("{ field: stepCount, lt: { setting: blast.limit } }", replier))
                .isEqualTo(Tri.TRUE);
        assertThat(eval("{ field: stepCount, lte: 1 }", replier)).isEqualTo(Tri.TRUE);
    }

    @Test
    void stringsListsAndPathsThroughReferences() throws Exception {
        assertThat(eval("{ field: name, startsWith: Rep }", replier)).isEqualTo(Tri.TRUE);
        assertThat(eval("{ field: name, contains: plie }", replier)).isEqualTo(Tri.TRUE);
        assertThat(eval("{ field: name, matches: \"^R.*r$\" }", replier)).isEqualTo(Tri.TRUE);
        assertThat(eval("{ field: approve, contains: Hook }", replier)).isEqualTo(Tri.TRUE);
        assertThat(eval("{ field: approve, contains: Files }", replier)).isEqualTo(Tri.FALSE);
        assertThat(eval("{ field: model.id, is: claude-haiku-5-5 }", replier)).isEqualTo(Tri.TRUE);
        assertThat(eval("{ field: model.floatingAlias, is: false }", replier)).isEqualTo(Tri.TRUE);
        assertThat(eval("{ field: name, is: { field: name } }", replier)).isEqualTo(Tri.TRUE);
    }

    @Test
    void existenceAndCounts() throws Exception {
        assertThat(eval("{ field: approve, exists: true }", replier)).isEqualTo(Tri.TRUE);
        assertThat(eval("{ field: approve, missing: true }", replier)).isEqualTo(Tri.FALSE);
        assertThat(eval("{ field: approve, missing: true }", plain)).isEqualTo(Tri.TRUE);
        assertThat(eval("{ field: tools, count: { is: 2 } }", replier)).isEqualTo(Tri.TRUE);
        assertThat(eval("{ field: tools, count: { gt: 2 } }", replier)).isEqualTo(Tri.FALSE);
        assertThat(eval("{ field: tools, count: { isNot: 0 } }", plain)).isEqualTo(Tri.FALSE);
    }

    @Test
    void quantifiersOverAListOfEntities() throws Exception {
        assertThat(eval("{ field: tools, some: { field: reach, is: sends } }", replier))
                .isEqualTo(Tri.TRUE);
        assertThat(eval("{ field: tools, every: { field: reach, is: sends } }", replier))
                .isEqualTo(Tri.FALSE);
        assertThat(eval("{ field: tools, none: { field: reach, is: writes } }", replier))
                .isEqualTo(Tri.TRUE);
        assertThat(eval("{ field: tools, every: { field: reach, is: sends } }", plain))
                .isEqualTo(Tri.TRUE); // vacuously true
        assertThat(eval("{ field: tools, some: { field: reach, is: sends } }", plain))
                .isEqualTo(Tri.FALSE);
    }

    @Test
    void combinatorsAreThreeValuedAndUnknownNeverBecomesTrueByNegation() throws Exception {
        assertThat(
                        eval(
                                "{ all: [ { field: hasTools, is: true }, { field: guardPii, is: warn } ] }",
                                replier))
                .isEqualTo(Tri.TRUE);
        assertThat(
                        eval(
                                "{ any: [ { field: hasTools, is: false }, { field: guardPii, is: warn } ] }",
                                replier))
                .isEqualTo(Tri.TRUE);
        assertThat(eval("{ not: { field: hasTools, is: true } }", replier)).isEqualTo(Tri.FALSE);
        // a setting nobody declared is unknown
        assertThat(eval("{ field: setting.data.personal, is: x }", replier)).isEqualTo(Tri.UNKNOWN);
        assertThat(eval("{ not: { field: setting.data.personal, is: x } }", replier))
                .isEqualTo(Tri.UNKNOWN);
        assertThat(
                        eval(
                                "{ all: [ { field: hasTools, is: true }, { field: setting.data.personal, is: x } ] }",
                                replier))
                .isEqualTo(Tri.UNKNOWN);
        assertThat(
                        eval(
                                "{ all: [ { field: hasTools, is: false }, { field: setting.data.personal, is: x } ] }",
                                replier))
                .isEqualTo(Tri.FALSE);
        assertThat(
                        eval(
                                "{ any: [ { field: hasTools, is: true }, { field: setting.data.personal, is: x } ] }",
                                replier))
                .isEqualTo(Tri.TRUE);
        assertThat(
                        eval(
                                "{ any: [ { field: hasTools, is: false }, { field: setting.data.personal, is: x } ] }",
                                replier))
                .isEqualTo(Tri.UNKNOWN);
    }

    @Test
    void aSettingHoldingACommaSeparatedListWorksWithIn() throws Exception {
        assertThat(eval("{ field: name, in: { setting: data.regions } }", replier))
                .isEqualTo(Tri.FALSE);
        assertThat(eval("{ field: setting.data.regions, is: \"eu, us\" }", replier))
                .isEqualTo(Tri.TRUE);
        assertThat(eval("{ field: model.provider, notIn: { setting: data.regions } }", replier))
                .isEqualTo(Tri.TRUE);
    }

    @Test
    void aRegularExpressionIsCutOffAfterAFixedNumberOfStepsAndIsUnknownNotAHang() throws Exception {
        // a provider adds an entity with a very long text, as a prompt will be in a later phase
        io.github.llm4j.loom.review.fact.FactProvider longText =
                new io.github.llm4j.loom.review.fact.FactProvider() {
                    public String name() {
                        return "long";
                    }

                    public void provide(
                            io.github.llm4j.loom.review.fact.FactInput in, FactBase.Builder out) {
                        Entity e =
                                out.add(
                                        "agent",
                                        "agent:long",
                                        io.github.llm4j.loom.review.model.Location.at("x", 1));
                        out.set(e, "name", "a".repeat(300_000));
                    }
                };
        java.nio.file.Files.createDirectories(dir.resolve("long"));
        FactBase big =
                FactBuilder.standard()
                        .with(longText)
                        .build(
                                ReviewFixtures.input(
                                        dir.resolve("long"), SCRIPT, java.util.Map.of(), true),
                                ReviewSettings.defaults());
        Entity victim = big.find("agent:long").orElseThrow();
        long t = System.nanoTime();
        assertThat(
                        Evaluator.evaluate(when("{ field: name, matches: \"^.*b$\" }"), victim, big)
                                .result())
                .isEqualTo(Tri.UNKNOWN);
        assertThat((System.nanoTime() - t) / 1_000_000).isLessThan(5000);
        // an ordinary expression on an ordinary text is unaffected
        assertThat(eval("{ field: name, matches: \"^R.*r$\" }", replier)).isEqualTo(Tri.TRUE);
        // an invalid expression that slipped past validation is unknown, not an exception
        assertThat(eval("{ field: name, matches: \"([\" }", replier)).isEqualTo(Tri.UNKNOWN);
    }

    @Test
    void anExplanationShowsEveryLeafWithTheValueItSaw() throws Exception {
        Evaluation e =
                Evaluator.evaluate(
                        when(
                                "{ all: [ { field: hasTools, is: true }, { field: guardPii, is: mask } ] }"),
                        replier,
                        facts);
        String text = e.render();
        assertThat(e.result()).isEqualTo(Tri.FALSE);
        assertThat(text)
                .contains("all of")
                .contains("hasTools is true: saw true")
                .contains("guardPii is mask: saw warn");
        Evaluation step = Evaluator.evaluate(when("{ field: retry, is: false }"), firstStep, facts);
        assertThat(step.result()).isEqualTo(Tri.FALSE);
        assertThat(step.text()).contains("retry is false").contains("saw true");
    }

    @Test
    void templatesFillFactsAndLeaveOtherBracesAlone() {
        assertThat(Template.render("{name} uses {model.id}; { pii: mask } stays", replier, facts))
                .isEqualTo("Replier uses claude-haiku-5-5; { pii: mask } stays");
        assertThat(Template.render("tools: {tools}", replier, facts))
                .isEqualTo("tools: Hook, web_search");
        assertThat(Template.render("none: {tools}", plain, facts)).isEqualTo("none: none");
        assertThat(Template.render("unset: {setting.data.personal}", replier, facts))
                .isEqualTo("unset: ?");
        assertThat(Template.render("fix: {setting.fix.retry}", replier, facts)).contains("retry 2");
        assertThat(Template.render(null, replier, facts)).isEmpty();
        assertThat(Template.placeholders("a {x} b {y.z} { not }")).containsExactly("x", "y.z");
    }
}
