package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.fact.Entity;
import io.github.llm4j.loom.review.fact.EntityDef;
import io.github.llm4j.loom.review.fact.FactBase;
import io.github.llm4j.loom.review.fact.FactBuilder;
import io.github.llm4j.loom.review.fact.FactModel;
import io.github.llm4j.loom.review.fact.FieldDef;
import io.github.llm4j.loom.review.fact.Unknown;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R59, R60 of spec 09: the facts Java computes, each exact and none left silently unset. */
class FactBuilderTest {

    @TempDir Path dir;

    static final String SCRIPT =
            """
            tool Hook { use: webhook  url: env.HOOK }
            tool Files { use: file  root: "data"  mode: read }
            agent Researcher { model: "gemini-flash-latest"  system: "Find things."  tools: [web_search, Files] }
            agent Replier {
                model: "claude-haiku-5-5"
                system: "Reply politely.
Short."
                tools: [Hook]
                approve: [Hook]
                guard { pii: warn }
            }
            agent Idle { model: "m"  system: "never used" }
            intentclassifier Intent {
                model: "claude-haiku-5-5"
                choose kind: refund | other
                confidence at least 0.8 else escalate
            }
            budget { tokens: 1000 }
            workflow Main(q) {
                intentclassify Intent on q -> intent retry 2 timeout 30s on_failure { return { x: "y" } }
                loop until (done == "yes") max 3 {
                    delegate "{q}" to Researcher -> found
                }
                parallel {
                    delegate "{found}" to Replier -> reply
                }
                return { reply: "{reply}" }
            }
            """;

    FactBase facts(String source) {
        return FactBuilder.standard()
                .build(ReviewFixtures.input(dir, source), ReviewSettings.defaults());
    }

    static Entity one(FactBase base, String id) {
        return base.find(id)
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "no entity "
                                                + id
                                                + " in "
                                                + base.entities("agent")
                                                + base.entities("tool")));
    }

    @Test
    void theScriptAndItsWorkflowsAreDescribed() {
        FactBase b = facts(SCRIPT);
        Entity script = one(b, "script");
        assertThat(script.get("file")).isEqualTo("main.loom");
        assertThat(script.get("workflowCount")).isEqualTo(1L);
        assertThat(script.get("agentCount")).isEqualTo(3L);
        assertThat(script.get("hasBudget")).isEqualTo(true);
        assertThat(script.get("isScheduled")).isEqualTo(false);
        assertThat(script.get("usesPausing")).isEqualTo(true); // Replier approves Hook
        Entity wf = one(b, "workflow:Main");
        assertThat(wf.get("hasReturn")).isEqualTo(true);
        assertThat((Long) wf.get("stepCount")).isGreaterThanOrEqualTo(6L);
        assertThat((Long) wf.get("maxDepth")).isEqualTo(2L);
    }

    @Test
    void agentsKnowTheirToolsReachGuardsAndPrompts() {
        FactBase b = facts(SCRIPT);
        Entity replier = one(b, "agent:Replier");
        assertThat(replier.get("hasTools")).isEqualTo(true);
        assertThat(replier.get("hasEffects")).isEqualTo(true);
        assertThat(replier.get("maxReach")).isEqualTo("sends");
        assertThat(replier.get("guardPii")).isEqualTo("warn");
        assertThat(replier.get("guardBias")).isEqualTo("none");
        assertThat(replier.get("promptSource")).isEqualTo("inline");
        assertThat(replier.get("inlinePromptLines")).isEqualTo(2L);
        assertThat(replier.get("approve")).isEqualTo(List.of("Hook"));
        assertThat(((Entity) replier.get("model")).get("floatingAlias")).isEqualTo(false);
        Entity researcher = one(b, "agent:Researcher");
        assertThat(researcher.get("maxReach"))
                .isEqualTo(
                        "fetches"); // web search fetches, the file tool only reads: the widest wins
        assertThat(((Entity) researcher.get("model")).get("floatingAlias")).isEqualTo(true);
        assertThat(one(b, "agent:Idle").get("hasTools")).isEqualTo(false);
        assertThat(one(b, "agent:Idle").get("maxReach")).isEqualTo("none");
    }

    @Test
    void toolsKnowTheirKindReachJournalingAndWhoUsesThem() {
        FactBase b = facts(SCRIPT);
        Entity hook = one(b, "tool:Hook");
        assertThat(hook.get("kind")).isEqualTo("webhook");
        assertThat(hook.get("reach")).isEqualTo("sends");
        assertThat(hook.get("journaled")).isEqualTo(true);
        assertThat(hook.get("approved")).isEqualTo(true);
        assertThat(((List<?>) hook.get("usedBy"))).hasSize(1);
        Entity files = one(b, "tool:Files");
        assertThat(files.get("reach")).isEqualTo("reads");
        assertThat(files.get("approved")).isEqualTo(false);
        Entity search = one(b, "tool:web_search");
        assertThat(search.get("kind")).isEqualTo("builtin");
        assertThat(search.get("reach")).isEqualTo("fetches");
    }

    @Test
    void aToolFromTheLootFileIsClassKindWithItsDeclaredReachAndAnUnknownOneIsAssumedToSend() {
        String script =
                "agent A { model: \"m\"  system: \"s\"  tools: [Mailer, Mystery] }\nworkflow Main(q) { delegate \"{q}\" to A -> r  note \"{r}\" }\n";
        FactBase b =
                FactBuilder.standard()
                        .build(
                                ReviewFixtures.input(dir, script, Map.of("Mailer", "sends"), true),
                                ReviewSettings.defaults());
        Entity mailer = one(b, "tool:Mailer");
        assertThat(mailer.get("kind")).isEqualTo("class");
        assertThat(mailer.get("reach")).isEqualTo("sends");
        assertThat(mailer.get("reachKnown")).isEqualTo(true);
        Entity mystery = one(b, "tool:Mystery");
        assertThat(mystery.get("kind")).isEqualTo("class");
        assertThat(mystery.get("reach")).isEqualTo("sends");
        assertThat(mystery.get("reachKnown")).isEqualTo(false);
    }

    @Test
    void stepsComeFromTheGraphWithItsIdsAndKnowRetriesTimeoutsLoopsAndHandlers() {
        FactBase b = facts(SCRIPT);
        List<Entity> steps = b.entities("step");
        Entity classify =
                steps.stream()
                        .filter(s -> s.get("kind").equals("classify"))
                        .findFirst()
                        .orElseThrow();
        assertThat(classify.where().nodeId()).matches("n\\d+");
        assertThat(classify.get("retry")).isEqualTo(true);
        assertThat(classify.get("retryCount")).isEqualTo(2L);
        assertThat(classify.get("timeout")).isEqualTo(true);
        assertThat(classify.get("timeoutMs")).isEqualTo(30000L);
        assertThat(classify.get("onFailure")).isEqualTo(true);
        Entity loop =
                steps.stream().filter(s -> s.get("kind").equals("loop")).findFirst().orElseThrow();
        assertThat(loop.get("hasMax")).isEqualTo(true);
        assertThat(loop.get("loopMax")).isEqualTo(3L);
        Entity inLoop =
                steps.stream()
                        .filter(
                                s ->
                                        s.get("kind").equals("delegate")
                                                && s.get("inLoop").equals(true))
                        .findFirst()
                        .orElseThrow();
        assertThat(inLoop.get("depth")).isEqualTo(1L);
        assertThat(((Entity) inLoop.get("agent")).get("name")).isEqualTo("Researcher");
        assertThat(inLoop.get("bindsVariable")).isEqualTo("found");
        assertThat(((Entity) classify.get("workflow")).get("name")).isEqualTo("Main");
    }

    @Test
    void agentsKnowHowManyStepsUseThemAndAnUnusedOneIsNotReferenced() {
        FactBase b = facts(SCRIPT);
        assertThat(one(b, "agent:Researcher").get("stepCount")).isEqualTo(1L);
        assertThat(one(b, "agent:Replier").get("referenced")).isEqualTo(true);
        assertThat(one(b, "agent:Idle").get("referenced")).isEqualTo(false);
        assertThat(one(b, "agent:Idle").get("stepCount")).isEqualTo(0L);
    }

    @Test
    void aLoopWithoutAMaximumSaysSo() {
        FactBase b =
                facts(
                        "agent A { model: \"m\"  system: \"s\" }\nworkflow Main(q) { loop until (x == \"y\") { delegate \"{q}\" to A -> x } }\n");
        Entity loop =
                b.entities("step").stream()
                        .filter(s -> s.get("kind").equals("loop"))
                        .findFirst()
                        .orElseThrow();
        assertThat(loop.get("hasMax")).isEqualTo(false);
        assertThat(loop.get("loopMax")).isEqualTo(0L);
    }

    @Test
    void classifiersAndTheirModelsAreDescribed() {
        FactBase b = facts(SCRIPT);
        Entity c = one(b, "classifier:Intent");
        assertThat(c.get("hasConfidenceRule")).isEqualTo(true);
        assertThat(c.get("questionCount")).isEqualTo(1L);
        assertThat(((Entity) c.get("model")).get("id")).isEqualTo("claude-haiku-5-5");
        // two agents and the classifier name the same model: one entity
        assertThat(b.entities("model").stream().filter(m -> m.get("id").equals("claude-haiku-5-5")))
                .hasSize(1);
    }

    @Test
    void withoutAGraphThereAreNoStepsAndStepDependentFactsAreUnknownNotFalse() {
        FactBase b =
                FactBuilder.standard()
                        .build(
                                ReviewFixtures.input(dir, SCRIPT, Map.of(), false),
                                ReviewSettings.defaults());
        assertThat(b.graphAvailable()).isFalse();
        assertThat(b.entities("step")).isEmpty();
        assertThat(one(b, "agent:Replier").get("stepCount")).isSameAs(Unknown.INSTANCE);
        assertThat(one(b, "agent:Replier").get("referenced")).isSameAs(Unknown.INSTANCE);
    }

    @Test
    void settingsAreReadAsStringsAndUndeclaredOnesAreUnknown() {
        FactBase b =
                FactBuilder.standard()
                        .build(
                                ReviewFixtures.input(dir, SCRIPT),
                                ReviewSettings.parse("p", "data.regions = eu\n"));
        assertThat(b.setting("data.regions")).isEqualTo("eu");
        assertThat(b.setting("data.personal")).isSameAs(Unknown.INSTANCE);
    }

    @Test
    void everyFactInTheCatalogIsSetOnEveryEntityExceptTheOnesThatMayBeUnknown() {
        FactModel model = FactModel.v1();
        FactBase b = facts(SCRIPT);
        for (EntityDef def : model.entities()) {
            for (Entity e : b.entities(def.kind())) {
                for (FieldDef f : def.fields()) {
                    boolean mayBeUnknown =
                            (def.kind().equals("step") && f.name().equals("agent"))
                                    // unknown for an agent whose prompt is not a file, or that
                                    // cannot be looked up
                                    || (def.kind().equals("agent")
                                            && f.name().equals("unversionedAmongVersioned"))
                                    // unknown until the project lists its deprecated models
                                    || (def.kind().equals("model")
                                            && f.name().equals("deprecated"));
                    if (!mayBeUnknown)
                        assertThat(e.get(f.name()))
                                .as("%s.%s", e.id(), f.name())
                                .isNotSameAs(Unknown.INSTANCE);
                }
            }
        }
    }

    @Test
    void theCatalogDescribesEveryFieldAndResolvesPaths() {
        FactModel m = FactModel.v1();
        for (EntityDef def : m.entities())
            for (FieldDef f : def.fields())
                assertThat(f.description()).as("%s.%s", def.kind(), f.name()).isNotBlank();
        assertThat(m.resolve("step", "agent.hasTools").valid()).isTrue();
        assertThat(m.resolve("step", "agent.model.floatingAlias").valid()).isTrue();
        assertThat(m.resolve("step", "setting.data.regions").setting()).isTrue();
        assertThat(m.resolve("step", "agent.hasTols").problem())
                .contains("did you mean 'hasTools'");
        assertThat(m.resolve("step", "retry.x").problem()).contains("not a reference");
        assertThat(m.resolve("step", "a.b.c.d").problem()).contains("more than three");
        assertThat(m.resolve("nope", "x").problem()).contains("unknown entity");
        assertThat(m.resolve("step", "setting").problem()).contains("needs a key");
    }
}
