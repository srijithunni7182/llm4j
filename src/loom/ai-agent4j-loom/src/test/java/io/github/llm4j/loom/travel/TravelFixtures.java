package io.github.llm4j.loom.travel;

import io.github.llm4j.loom.wtest.LoomRun;
import io.github.llm4j.loom.wtest.LoomTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Scripts that cover the statement kinds, each with the scripted answers that make a real run of
 * it, so a test can make a run, then explain, replay or change it.
 */
public final class TravelFixtures {

    private TravelFixtures() {}

    /** One script and how to run it for real (scripted). */
    public record Case(
            String name,
            String script,
            String workflow,
            Map<String, String> input,
            UnaryOperator<LoomTest> scripted) {}

    public static final String TRIAGE =
            """
            intentclassifier Intent {
                model: "m"
                choose kind: order_status | refund | other
                ask urgent: "Is the customer threatening to leave?"
            }
            agent Replier { model: "m" system: "reply" }
            workflow Main(message) {
                intentclassify Intent on message -> intent
                alt (intent.urgent == "yes") {
                    note "urgent"
                    return "retention"
                } else {
                    alt (intent.kind == "order_status") {
                        delegate "Reply to {message}" to Replier -> reply
                        return reply
                    } else {
                        return "other"
                    }
                }
            }
            """;

    public static final List<Case> CORPUS =
            List.of(
                    new Case(
                            "straight",
                            """
                            agent A { model: "m" system: "x" }
                            workflow Main(topic) {
                                delegate "Write about {topic}" to A -> draft
                                return draft
                            }
                            """,
                            "Main",
                            Map.of("topic", "cats"),
                            t -> t.model("A", "a draft")),
                    new Case(
                            "triage order status",
                            TRIAGE,
                            "Main",
                            Map.of("message", "where is my parcel?"),
                            t ->
                                    t.classifier(
                                                    "Intent",
                                                    Map.of("kind", "order_status", "urgent", "no"))
                                            .model("Replier", "it is on its way")),
                    new Case(
                            "triage urgent",
                            TRIAGE,
                            "Main",
                            Map.of("message", "I will cancel!"),
                            t -> t.classifier("Intent", Map.of("kind", "refund", "urgent", "yes"))),
                    new Case(
                            "triage other",
                            TRIAGE,
                            "Main",
                            Map.of("message", "hello"),
                            t -> t.classifier("Intent", Map.of("kind", "other", "urgent", "no"))),
                    new Case(
                            "loop until done",
                            """
                            agent Checker { model: "m" system: "x" }
                            workflow Main(topic) {
                                loop until (done == "yes") max 5 {
                                    delegate "Is {topic} done?" to Checker -> done
                                }
                                return done
                            }
                            """,
                            "Main",
                            Map.of("topic", "t"),
                            t -> t.model("Checker", "no", "no", "yes")),
                    new Case(
                            "loop runs out",
                            """
                            agent Checker { model: "m" system: "x" }
                            workflow Main(topic) {
                                loop until (done == "yes") max 2 {
                                    delegate "Is {topic} done?" to Checker -> done
                                } on_exhausted {
                                    note "gave up"
                                }
                                return done
                            }
                            """,
                            "Main",
                            Map.of("topic", "t"),
                            t -> t.model("Checker", "no", "no")),
                    new Case(
                            "for each",
                            """
                            agent Planner { model: "m" output_schema: { items: list<string> } }
                            agent Worker { model: "m" system: "x" }
                            workflow Main(topic) {
                                delegate "Plan {topic}" to Planner -> plan
                                for each item in plan.items {
                                    delegate "Do {item}" to Worker -> out
                                }
                                return out
                            }
                            """,
                            "Main",
                            Map.of("topic", "t"),
                            t ->
                                    t.typed("Planner", Map.of("items", List.of("a", "b")))
                                            .model("Worker", "done a", "done b")),
                    new Case(
                            "human answer",
                            """
                            agent A { model: "m" system: "x" }
                            workflow Main(topic) {
                                delegate "Draft {topic}" to A -> draft
                                human_prompt "Approve {draft}?" -> approval
                                alt (approval == "yes") {
                                    return "published"
                                } else {
                                    return "rejected"
                                }
                            }
                            """,
                            "Main",
                            Map.of("topic", "t"),
                            t -> t.model("A", "a draft").humans("yes")),
                    new Case(
                            "call another workflow",
                            """
                            agent A { model: "m" system: "x" }
                            workflow Main(topic) {
                                call Helper(subject = topic) -> helped
                                return helped
                            }
                            workflow Helper(subject) {
                                delegate "Help with {subject}" to A -> help
                                return help
                            }
                            """,
                            "Main",
                            Map.of("topic", "t"),
                            t -> t.model("A", "helped")),
                    new Case(
                            "failure taken over",
                            """
                            agent A { model: "m" system: "x" }
                            workflow Main(topic) {
                                delegate "Try {topic}" to A -> out retry 1 on_failure {
                                    note "fell back"
                                    set out = "fallback"
                                }
                                return out
                            }
                            """,
                            "Main",
                            Map.of("topic", "t"),
                            t ->
                                    t.model("A", "never")
                                            .failing("A", LoomTest.Failure.always("server_error"))),
                    new Case(
                            "task result",
                            """
                            agent A { model: "m" system: "x" }
                            workflow Main(topic) {
                                run Fetch(subject = topic) -> data
                                alt (data.found == "yes") {
                                    delegate "Use {data.title}" to A -> used
                                    return used
                                } else {
                                    return "nothing"
                                }
                            }
                            """,
                            "Main",
                            Map.of("topic", "t"),
                            t ->
                                    t.task("Fetch", Map.of("found", "yes", "title", "A title"))
                                            .model("A", "used it")),
                    new Case(
                            "parallel branches",
                            """
                            agent A { model: "m" system: "x" }
                            agent B { model: "m" system: "x" }
                            workflow Main(topic) {
                                parallel {
                                    delegate "A on {topic}" to A -> a
                                    delegate "B on {topic}" to B -> b
                                }
                                return a
                            }
                            """,
                            "Main",
                            Map.of("topic", "t"),
                            t -> t.model("A", "from a").model("B", "from b")),
                    new Case(
                            "rewind",
                            """
                            agent Writer { model: "m" system: "x" }
                            workflow Main(topic) {
                                checkpoint draft
                                delegate "Write {topic}" to Writer -> text
                                rewind to draft when (text == "bad") at most 1 times
                                return text
                            }
                            """,
                            "Main",
                            Map.of("topic", "t"),
                            t -> t.model("Writer", "bad", "good")));

    /** Writes the case's script into {@code dir} and runs it for real (scripted). */
    public static Run run(Path dir, Case c) throws IOException {
        Path file = dir.resolve(c.name().replace(' ', '-') + ".loom");
        Files.writeString(file, c.script());
        LoomRun run = c.scripted().apply(LoomTest.of(file)).run(c.workflow(), c.input());
        return new Run(file, c, run);
    }

    public record Run(Path script, Case source, LoomRun run) {}

    /**
     * Writes the run as a run directory ({@code run.json} and {@code journal.json}) the way {@code
     * weave run --journal} leaves one, with the script's fingerprint, and returns the folder.
     */
    public static Path runDir(Path base, Run run) throws IOException {
        Path dir = base.resolve("run-" + run.source().name().replace(' ', '-'));
        Files.createDirectories(dir);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.Map<String, Object> spec = new java.util.LinkedHashMap<>();
        spec.put("script", run.script().toString());
        spec.put("workflow", run.source().workflow());
        spec.put("inputs", run.source().input());
        spec.put("lenient", false);
        spec.put("scriptHash", io.github.llm4j.loom.travel.ScriptHash.of(run.script()));
        Files.writeString(dir.resolve("run.json"), json.writeValueAsString(spec));
        io.github.llm4j.loom.runtime.FileRunJournal journal =
                new io.github.llm4j.loom.runtime.FileRunJournal(dir.resolve("journal.json"));
        run.run().journal().all().forEach(journal::put);
        return dir;
    }
}
