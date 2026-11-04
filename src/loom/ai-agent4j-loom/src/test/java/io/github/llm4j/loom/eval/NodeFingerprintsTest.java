package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.LoomLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R6 of docs/specs/28-evaluation-first: what determines a node's answers, and nothing else. */
class NodeFingerprintsTest {

    @TempDir Path dir;

    private static final String BASE =
            """
            shared_prompt: "Be kind."
            evaluator Compliance { model: "gemini-2.5-flash" }
            policy HouseRules: "house-rules" checked by Compliance
            extractor Invoice {
                model: "gemini-2.5-flash"
                instructions: "Read the invoice."
                fields {
                    vendor: string
                    total: number
                }
                optional: [total]
            }
            tool Mail { use: webhook  url: env.HOOK  effect: changes }
            agent Writer { model: "gemini-2.5-flash"  system: "Write well."  temperature: 0.2  tools: [Mail] }
            agent Other { model: "gemini-2.5-flash"  system: "Other." }
            workflow Main(q) {
                delegate "{q}" to Writer -> w
                delegate "{q}" to Other -> o
                extract Invoice from w -> i
                check w against HouseRules using Compliance -> v on_violation { fail }
                return w
            }
            """;

    private NodeFingerprints.Fingerprints fp(String source, String rule) throws Exception {
        Path policies = Files.createDirectories(dir.resolve("policies"));
        Files.writeString(policies.resolve("house-rules.md"), "## r\n" + rule + "\n");
        Path script = Files.writeString(dir.resolve("main.loom"), source);
        return NodeFingerprints.of(new LoomLoader().load(script.toString()), script);
    }

    private NodeFingerprints.Fingerprints fp(String source) throws Exception {
        return fp(source, "Never promise a refund.");
    }

    private Map<String, List<String>> diff(NodeFingerprints.Fingerprints before, NodeFingerprints.Fingerprints after) {
        return NodeFingerprints.changed(after, Optional.of(before));
    }

    // ── what counts ──────────────────────────────────────────────────────────────────────────

    @Test
    void theSamePromptTextGivesTheSameFingerprint() throws Exception {
        assertThat(diff(fp(BASE), fp(BASE))).isEmpty();
    }

    @Test
    void anAgentsPromptModelTemperatureSchemaAndToolsEachChangeIt() throws Exception {
        var base = fp(BASE);
        assertThat(diff(base, fp(BASE.replace("Write well.", "Write badly.")))).containsOnlyKeys("Writer").containsEntry("Writer", List.of("prompt"));
        assertThat(diff(base, fp(BASE.replace("agent Writer { model: \"gemini-2.5-flash\"", "agent Writer { model: \"gemini-2.5-pro\"")))).containsEntry("Writer", List.of("model"));
        assertThat(diff(base, fp(BASE.replace("temperature: 0.2", "temperature: 0.9")))).containsEntry("Writer", List.of("temperature"));
        assertThat(diff(base, fp(BASE.replace("tools: [Mail] }", "tools: [Mail]  output_schema: { a: string } }")))).containsEntry("Writer", List.of("schema"));
        assertThat(diff(base, fp(BASE.replace("effect: changes", "effect: reads")))).containsEntry("Writer", List.of("tools"));
    }

    @Test
    void theSharedPromptChangesEveryAgentThatReceivesIt() throws Exception {
        var changed = diff(fp(BASE), fp(BASE.replace("Be kind.", "Be brief.")));
        assertThat(changed).containsOnlyKeys("Writer", "Other");
        assertThat(changed.get("Writer")).containsExactly("prompt");
    }

    @Test
    void anExtractorsInstructionsFieldsAndOptionalChangeIt() throws Exception {
        var base = fp(BASE);
        assertThat(diff(base, fp(BASE.replace("Read the invoice.", "Read it twice.")))).containsOnlyKeys("Invoice").containsEntry("Invoice", List.of("prompt"));
        assertThat(diff(base, fp(BASE.replace("vendor: string", "vendor: number")))).containsEntry("Invoice", List.of("schema"));
        assertThat(diff(base, fp(BASE.replace("optional: [total]", "optional: [vendor]")))).containsEntry("Invoice", List.of("schema"));
    }

    @Test
    void anEvaluatorsPolicyVersionHashChangesIt() throws Exception {
        var changed = diff(fp(BASE), fp(BASE, "Never promise money back."));
        assertThat(changed).containsOnlyKeys("Compliance").containsEntry("Compliance", List.of("policies"));
    }

    @Test
    void aNodesOwnCasesChangeItButAnotherNodesDoNot() throws Exception {
        var base = fp(BASE);
        Path g = Files.createDirectories(dir.resolve("eval/golden"));
        Files.writeString(g.resolve("writer.yaml"), "- id: w1\n  input: \"x\"\n  rubric: [\"a\"]\n");
        assertThat(diff(base, fp(BASE))).containsOnlyKeys("Writer").containsEntry("Writer", List.of("cases"));
    }

    // ── what does not ────────────────────────────────────────────────────────────────────────

    @Test
    void aCommentAndWhitespaceInTheScriptDoNotChangeAnything() throws Exception {
        String noisy = "// a note\n" + BASE.replace("workflow Main(q) {", "\n\n   workflow Main(q) {   ") + "\n// the end\n";
        assertThat(diff(fp(BASE), fp(noisy))).isEmpty();
    }

    @Test
    void anotherNodesPromptDoesNotChangeThisOne() throws Exception {
        assertThat(diff(fp(BASE), fp(BASE.replace("Other.", "Something else.")))).containsOnlyKeys("Other");
    }

    // ── fail closed, and the reference ───────────────────────────────────────────────────────

    @Test
    void anUnreadablePromptFileCountsAsChangedEveryTime() throws Exception {
        String source = BASE.replace("system: \"Write well.\"", "prompt: \"missing\"");
        var a = fp(source);
        var b = fp(source);
        assertThat(diff(a, b)).containsKey("Writer");
    }

    @Test
    void withNoReferenceEverythingCountsAsChanged() throws Exception {
        var current = fp(BASE);
        var changed = NodeFingerprints.changed(current, Optional.empty());
        assertThat(changed.keySet()).containsExactlyInAnyOrderElementsOf(current.nodes().keySet());
        assertThat(changed.values()).allSatisfy(parts -> assertThat(parts).containsExactly("no reference"));
    }

    @Test
    void aNodeTheReferenceDidNotHaveIsNew() throws Exception {
        var before = new NodeFingerprints.Fingerprints(Map.of("Writer", fp(BASE).nodes().get("Writer")));
        var changed = NodeFingerprints.changed(fp(BASE), Optional.of(before));
        assertThat(changed).containsEntry("Other", List.of("new node")).doesNotContainKey("Writer");
    }

    @Test
    void aRecordRoundTripsAndAnOlderOrBrokenRecordIsNoReference() throws Exception {
        Path file = dir.resolve("eval/last-run-fingerprints.json");
        var current = fp(BASE);
        NodeFingerprints.write(file, current);
        assertThat(NodeFingerprints.read(file)).contains(current);
        Files.writeString(file, "not json");
        assertThat(NodeFingerprints.read(file)).isEmpty();
        Files.writeString(file, "{}");
        assertThat(NodeFingerprints.read(file)).isEmpty();
        assertThat(NodeFingerprints.read(dir.resolve("nope.json"))).isEmpty();
    }

    @Test
    void aPromptFileIsPartOfThePromptAndTheSameFileGivesTheSameFingerprint() throws Exception {
        Files.createDirectories(dir.resolve("prompts"));
        Files.writeString(dir.resolve("prompts/writer.md"), "Write well.");
        String source = BASE.replace("system: \"Write well.\"", "prompt: \"writer\"");
        var a = fp(source);
        assertThat(diff(a, fp(source))).isEmpty();
        Files.writeString(dir.resolve("prompts/writer.md"), "Write badly.");
        assertThat(diff(a, fp(source))).containsEntry("Writer", List.of("prompt"));
    }

    @Test
    void aSchemaChangeInsideAnEnumOrAListElementChangesTheFingerprint() throws Exception {
        String enumSource = BASE.replace("tools: [Mail] }", "tools: [Mail]  output_schema: { kind: enum[\"a\", \"b\"] } }");
        assertThat(diff(fp(enumSource), fp(enumSource.replace("\"b\"", "\"c\"")))).containsEntry("Writer", List.of("schema"));
        String listSource = BASE.replace("tools: [Mail] }", "tools: [Mail]  output_schema: { items: list<string> } }");
        assertThat(diff(fp(listSource), fp(listSource.replace("list<string>", "list<number>")))).containsEntry("Writer", List.of("schema"));
    }

    @Test
    void anEvaluatorsModelAndSettingsChangeIt() throws Exception {
        var base = fp(BASE);
        assertThat(diff(base, fp(BASE.replace("evaluator Compliance { model: \"gemini-2.5-flash\" }", "evaluator Compliance { model: \"gemini-2.5-pro\" }")))).containsEntry("Compliance", List.of("model"));
        assertThat(diff(base, fp(BASE.replace("evaluator Compliance { model: \"gemini-2.5-flash\" }", "evaluator Compliance { model: \"gemini-2.5-flash\" samples: 3 }")))).containsEntry("Compliance", List.of("settings"));
    }

    @Test
    void aWorkflowReachesANodeItDelegatesToExtractsFromOrChecksWithAndThroughCalls() throws Exception {
        String source =
                """
                evaluator Compliance { model: "gemini-2.5-flash" }
                policy HouseRules: "house-rules" checked by Compliance
                extractor Invoice { model: "gemini-2.5-flash"  fields { a: string } }
                agent Writer { model: "gemini-2.5-flash"  system: "w" }
                agent Lonely { model: "gemini-2.5-flash"  system: "l" }
                workflow Inner(q) { delegate "{q}" to Writer -> w  return w }
                workflow Outer(q) { call Inner(q = q) -> r  return r }
                workflow Reads(q) { extract Invoice from q -> i  return i }
                workflow Judges(q) { check q against HouseRules using Compliance -> v on_violation { fail }  return q }
                workflow Alone(q) { delegate "{q}" to Lonely -> l  return l }
                """;
        Path policies = Files.createDirectories(dir.resolve("policies"));
        Files.writeString(policies.resolve("house-rules.md"), "## r\nx\n");
        Path script = Files.writeString(dir.resolve("main.loom"), source);
        var loaded = new LoomLoader().load(script.toString());
        assertThat(NodeFingerprints.workflowsReaching(loaded, List.of("Writer"))).containsExactly("Inner", "Outer");
        assertThat(NodeFingerprints.workflowsReaching(loaded, List.of("Invoice"))).containsExactly("Reads");
        assertThat(NodeFingerprints.workflowsReaching(loaded, List.of("Compliance"))).containsExactly("Judges");
        assertThat(NodeFingerprints.workflowsReaching(loaded, List.of("Nobody"))).isEmpty();
    }

    @Test
    void theLastRunRecordSitsBesideTheGoldenFolder() throws Exception {
        Path script = Files.writeString(dir.resolve("main.loom"), BASE);
        assertThat(NodeFingerprints.lastRunFile(script)).isEqualTo(dir.resolve("eval/last-run-fingerprints.json").toAbsolutePath().normalize());
    }
}
