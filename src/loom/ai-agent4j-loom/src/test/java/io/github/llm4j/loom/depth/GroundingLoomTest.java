package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.grounding.Citation;
import io.github.llm4j.grounding.Recency;
import io.github.llm4j.grounding.SearchUsage;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoadException;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.security.Finding;
import io.github.llm4j.loom.security.SecurityAudit;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 8g in Loom (acceptance A12 and DESIGN §5): {@code grounding:} on an agent, the checks that
 * refuse it where it cannot work, the sources in {@code <name>_citations}, the {@code searches}
 * budget, the trace fields and the audit rule.
 */
class GroundingLoomTest {

    @TempDir Path dir;

    /** A model that searches when asked: grounded requests come back with two sources. */
    private static final class Searching implements LLMClient {
        final List<LLMRequest> seen = new ArrayList<>();
        final int searches;

        Searching(int searches) {
            this.searches = searches;
        }

        @Override
        public LLMResponse chat(LLMRequest request) {
            seen.add(request);
            LLMResponse.Builder b =
                    LLMResponse.builder()
                            .content(Harness.done("The rate is 5.50%."))
                            .model("test/model")
                            .tokenUsage(10, 5, 15)
                            .finishReason("stop");
            if (request.getGrounding() != null) {
                b.citations(
                                List.of(
                                        new Citation(
                                                1,
                                                "https://www.rbi.org.in/a",
                                                "RBI",
                                                "the rate",
                                                List.of()),
                                        new Citation(
                                                2, "https://example.org/b", null, null, List.of())))
                        .searches(SearchUsage.of(searches));
            }
            return b.build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            return Stream.of(chat(request));
        }
    }

    private HarnessExecutor run(Harness h, Searching model, String source) {
        HarnessExecutor e = h.executor(source, n -> model, null);
        e.initialize();
        return e;
    }

    private static List<Finding> audit(String source) {
        var script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        return SecurityAudit.audit(script, "w.loom").findings();
    }

    private static final String RESEARCH =
            """
            agent Researcher { model: "perplexity/openai/gpt-5.6-sol"  grounding: web { allow: ["rbi.org.in", "who.int"]  recency: year  country: "IN" } }
            workflow Main() { delegate "What is the repo rate?" to Researcher -> out }
            """;

    // ── syntax ───────────────────────────────────────────────────────────────────────────────

    @Test
    void groundingIsParsedWithItsSettings() {
        var script = new LoomParser(new Lexer(RESEARCH).tokenize()).parseScript();
        var g = script.getAgents().get(0).getGrounding();
        assertThat(g.getAllow()).containsExactly("rbi.org.in", "who.int");
        assertThat(g.getRecency()).isEqualTo("year");
        assertThat(g.getCountry()).isEqualTo("IN");
        assertThat(g.getMaxSearches()).isNull();
        var plain =
                new LoomParser(
                                new Lexer(
                                                "agent A { model: \"claude-haiku-5-5\"  grounding: web }\nworkflow M() { note \"x\" }")
                                        .tokenize())
                        .parseScript();
        assertThat(plain.getAgents().get(0).getGrounding().getAllow()).isEmpty();
    }

    @Test
    void aBadGroundingIsAParseErrorWithALine() {
        for (String bad :
                new String[] {
                    "agent A { model: \"claude-haiku-5-5\"  grounding: image }",
                    "agent A { model: \"claude-haiku-5-5\"  grounding: web  grounding: web }",
                    "agent A { model: \"claude-haiku-5-5\"  grounding: web { allow: who.int } }",
                    "agent A { model: \"claude-haiku-5-5\"  grounding: web { recency: year recency: day } }"
                }) {
            assertThatThrownBy(
                            () ->
                                    new LoomParser(
                                                    new Lexer(bad + "\nworkflow M() { note \"x\" }")
                                                            .tokenize())
                                            .parseScript())
                    .as(bad)
                    .hasMessageContaining("line 1");
        }
    }

    // ── weave check ──────────────────────────────────────────────────────────────────────────

    private String problems(String source) {
        Harness h = new Harness(dir);
        h.env.put("K", "a-key");
        try {
            h.ready(source);
            return "";
        } catch (LoomLoadException e) {
            return e.getMessage();
        }
    }

    @Test
    void aSupportedModelAndSettingsPass() {
        assertThat(problems(RESEARCH)).isEmpty();
        assertThat(
                        problems(
                                "agent A { model: \"claude-haiku-5-5\"  grounding: web { allow: [\"who.int\"] max_searches: 3 country: \"IN\" } }\nworkflow M() { note \"x\" }"))
                .isEmpty();
        assertThat(
                        problems(
                                "agent A { model: \"gemini-3.7-flash\"  grounding: web { recency: week } }\nworkflow M() { note \"x\" }"))
                .isEmpty();
        assertThat(
                        problems(
                                "provider Oai { use: openai  api_key: env.K  api: responses }\nagent A { model: \"Oai/gpt-6-luna\"  grounding: web { max_searches: 2 } }\nworkflow M() { note \"x\" }"))
                .isEmpty();
    }

    @Test
    void aModelWhoseProviderCannotSearchIsRefusedNamingModelAndProvider() {
        String ollama =
                problems(
                        "agent A { model: \"ollama/llama3\"  grounding: web }\nworkflow M() { note \"x\" }");
        assertThat(ollama)
                .contains("grounding needs a model whose provider can search the web")
                .contains("ollama/llama3")
                .contains("ollama");
        String openai =
                problems(
                        "agent A { model: \"gpt-6-luna\"  grounding: web }\nworkflow M() { note \"x\" }");
        assertThat(openai)
                .contains("gpt-6-luna")
                .contains("declare a provider with use: openai  api: responses");
        String chat =
                problems(
                        "provider Oai { use: openai  api_key: env.K }\nagent A { model: \"Oai/gpt-6-luna\"  grounding: web }\nworkflow M() { note \"x\" }");
        assertThat(chat).contains("provider Oai (use: openai)").contains("cannot");
        assertThat(
                        problems(
                                "agent A { model: \"sarvam/sarvam-m\"  grounding: web }\nworkflow M() { note \"x\" }"))
                .contains("sarvam");
    }

    @Test
    void aSettingTheProviderCannotHonourIsRefusedByName() {
        String perplexity =
                problems(
                        "agent A { model: \"perplexity/openai/gpt-5.6-sol\"  grounding: web { max_searches: 2 } }\nworkflow M() { note \"x\" }");
        assertThat(perplexity)
                .contains(
                        "grounding max_searches is not supported by perplexity/openai/gpt-5.6-sol")
                .contains("honours [allow, block, recency, country]");
        String gemini =
                problems(
                        "agent A { model: \"gemini-3.7-flash\"  grounding: web { allow: [\"who.int\"]  max_searches: 2 } }\nworkflow M() { note \"x\" }");
        assertThat(gemini)
                .contains("grounding allow is not supported")
                .contains("grounding max_searches is not supported")
                .contains("honours [recency]");
        String claude =
                problems(
                        "agent A { model: \"claude-haiku-5-5\"  grounding: web { recency: day } }\nworkflow M() { note \"x\" }");
        assertThat(claude).contains("grounding recency is not supported by claude-haiku-5-5");
        String xai =
                problems(
                        "provider G { use: xai  api_key: env.K  api: responses }\nagent A { model: \"G/grok-4.3\"  grounding: web { country: \"IN\" } }\nworkflow M() { note \"x\" }");
        assertThat(xai)
                .contains("grounding country is not supported by G/grok-4.3")
                .contains("honours [allow, block]");
    }

    @Test
    void theSettingsThemselvesAreChecked() {
        String p =
                problems(
                        """
                agent A { model: "claude-haiku-5-5"  grounding: web { allow: ["a.com"]  block: ["b.com"] } }
                agent B { model: "claude-haiku-5-5"  grounding: web { allow: [] } }
                agent C { model: "claude-haiku-5-5"  grounding: web { country: "India"  max_searches: 0 } }
                agent D { model: "gemini-3.7-flash"  grounding: web { recency: decade } }
                agent E { model: "claude-haiku-5-5"  grounding: web { filter: "x" } }
                workflow M() { note "x" }
                """);
        assertThat(p)
                .contains("grounding takes allow or block, not both")
                .contains("grounding allow names no domain")
                .contains("two-letter country code")
                .contains("max_searches must be a whole number of at least 1")
                .contains("recency must be day, week, month or year, got decade")
                .contains("unknown grounding setting filter");
    }

    @Test
    void everyModelOfARoutedAgentIsChecked() {
        String p =
                problems(
                        """
                routing Pick { strategy: fallback  primary: "claude-haiku-5-5"  fallback: ["ollama/llama3"] }
                agent A { routing: Pick  grounding: web }
                workflow M() { note "x" }
                """);
        assertThat(p).contains("ollama/llama3");
    }

    // ── a run ────────────────────────────────────────────────────────────────────────────────

    @Test
    void theAgentSearchesAndTheScriptGetsTheSources() {
        Harness h = new Harness(dir);
        Searching model = new Searching(2);
        HarnessExecutor e = run(h, model, RESEARCH);
        e.executeWorkflow("Main", Map.of());

        var sent = model.seen.get(0).getGrounding();
        assertThat(sent.allowDomains()).containsExactly("rbi.org.in", "who.int");
        assertThat(sent.recency()).isEqualTo(Recency.YEAR);
        assertThat(sent.country()).isEqualTo("IN");
        assertThat(e.getContext().getVariable("out")).isEqualTo("The rate is 5.50%.");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources =
                (List<Map<String, Object>>) e.getContext().getVariable("out_citations");
        assertThat(sources).hasSize(2);
        assertThat(sources.get(0))
                .containsEntry("index", 1)
                .containsEntry("url", "https://www.rbi.org.in/a")
                .containsEntry("title", "RBI");
        assertThat(sources.get(1)).doesNotContainKey("title");
    }

    @Test
    void anAgentWithoutGroundingGetsNoSources() {
        Harness h = new Harness(dir);
        Searching model = new Searching(1);
        HarnessExecutor e =
                run(
                        h,
                        model,
                        "agent A { model: \"claude-haiku-5-5\" }\nworkflow Main() { delegate \"x\" to A -> out }");
        e.executeWorkflow("Main", Map.of());
        assertThat(model.seen.get(0).getGrounding()).isNull();
        assertThat(e.getContext().getVariable("out_citations")).isIn(null, "");
    }

    @Test
    void theTraceCarriesCountsNeverText() {
        Harness h = new Harness(dir);
        HarnessExecutor e = run(h, new Searching(2), RESEARCH);
        e.executeWorkflow("Main", Map.of());
        TraceEvent call =
                h.trace.stream()
                        .filter(t -> t.type().equals(TraceEvent.MODEL_CALL))
                        .findFirst()
                        .orElseThrow();
        assertThat(call.data()).containsEntry("searches", 2).containsEntry("citations", 2);
        assertThat(call.data().toString()).doesNotContain("rbi.org.in").doesNotContain("the rate");
        TraceEvent end =
                h.trace.stream()
                        .filter(t -> t.type().equals(TraceEvent.DELEGATE_END))
                        .findFirst()
                        .orElseThrow();
        assertThat(end.data()).containsEntry("searches", 2L).containsEntry("citations", 2);
        TraceEvent runEnd =
                h.trace.stream()
                        .filter(t -> t.type().equals(TraceEvent.RUN_END))
                        .findFirst()
                        .orElseThrow();
        assertThat(runEnd.data()).containsEntry("searches", 2L);
    }

    @Test
    void aRecordedStepKeepsItsSourcesWhenTheRunIsReplayed() {
        Harness h = new Harness(dir);
        h.journal = io.github.llm4j.loom.runtime.RunJournal.inMemory();
        Searching model = new Searching(1);
        HarnessExecutor first = run(h, model, RESEARCH);
        first.executeWorkflow("Main", Map.of());
        int calls = model.seen.size();

        Harness second = new Harness(dir);
        second.journal = h.journal;
        HarnessExecutor again = run(second, model, RESEARCH);
        again.executeWorkflow("Main", Map.of());
        assertThat(model.seen).hasSize(calls); // nothing was asked again
        assertThat(again.getContext().getVariable("out_citations")).isNotNull();
    }

    // ── budgets ──────────────────────────────────────────────────────────────────────────────

    @Test
    void aSearchBudgetStopsAGroundedAgentAndShowsInTheRunBudget() {
        Harness h = new Harness(dir);
        Searching model = new Searching(1);
        HarnessExecutor e =
                run(
                        h,
                        model,
                        """
                budget { searches: 2 }
                agent Researcher { model: "claude-haiku-5-5"  grounding: web }
                workflow Main() {
                  delegate "one" to Researcher -> a
                  delegate "two" to Researcher -> b
                  set used = "{_budget.searches}"
                  delegate "three" to Researcher -> c
                }
                """);
        assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of()))
                .hasMessageContaining("searches");
        assertThat(model.seen).hasSize(2);
        assertThat(e.getContext().getVariable("used")).isEqualTo("2");
    }

    @Test
    void aStepAndAnAgentCanLimitSearchesToo() {
        var script =
                new LoomParser(
                                new Lexer(
                                                """
                agent R { model: "claude-haiku-5-5"  grounding: web  budget { searches: 3 } }
                workflow M() { delegate "x" to R -> out budget 1 searches }
                """)
                                        .tokenize())
                        .parseScript();
        assertThat(script.getAgents().get(0).getBudget().getSearches()).isEqualTo(3L);
        var step =
                (io.github.llm4j.loom.ast.DelegateStmt)
                        script.getWorkflows().get(0).getStatements().get(0);
        assertThat(step.getBudget().getSearches()).isEqualTo(1L);
    }

    // ── audit (R10, A12) ─────────────────────────────────────────────────────────────────────

    @Test
    void aGroundedAgentIsListedAsReadingTheWebWithOrWithoutAnAllowList() {
        List<Finding> open =
                audit(
                        "agent A { model: \"claude-haiku-5-5\"  grounding: web }\nworkflow M() { note \"x\" }");
        Finding f = open.stream().filter(x -> x.rule().equals("LA20")).findFirst().orElseThrow();
        assertThat(f.severity()).isEqualTo(io.github.llm4j.loom.security.Severity.LOW);
        assertThat(f.risk())
                .contains("Anthropic")
                .contains("queries the model writes")
                .contains("Nothing narrows");
        assertThat(f.where()).isEqualTo("A");

        List<Finding> narrowed =
                audit(
                        "agent A { model: \"claude-haiku-5-5\"  grounding: web { allow: [\"who.int\"] } }\nworkflow M() { note \"x\" }");
        Finding g =
                narrowed.stream().filter(x -> x.rule().equals("LA20")).findFirst().orElseThrow();
        assertThat(g.severity()).isEqualTo(io.github.llm4j.loom.security.Severity.INFO);
        assertThat(g.risk()).contains("does not make those pages trusted").contains("who.int");
    }

    @Test
    void anAllowListDoesNotRemoveTheUntrustedLegOfTheTrifecta() {
        String source =
                """
                tool Files { use: file  root: "."  mode: readwrite }
                tool Mailer { use: email  host: "smtp.example.com"  username: env.U  password: env.P }
                agent A { model: "claude-haiku-5-5"  tools: [Files, Mailer]  grounding: web { allow: ["who.int"] } }
                workflow M() { note "x" }
                """;
        List<Finding> found = audit(source);
        assertThat(found)
                .anySatisfy(
                        f -> {
                            assertThat(f.rule()).isEqualTo("LA01");
                            assertThat(f.risk()).contains("grounding web (allow: who.int)");
                        });
        // without the grounding there is no untrusted leg here
        assertThat(audit(source.replace("  grounding: web { allow: [\"who.int\"] }", "")))
                .noneMatch(f -> f.rule().equals("LA01"));
    }

    @Test
    void anAgentWithoutGroundingHasNoSuchFinding() {
        assertThat(audit("agent A { model: \"claude-haiku-5-5\" }\nworkflow M() { note \"x\" }"))
                .noneMatch(f -> f.rule().equals("LA20"));
    }

    @Test
    void theExplanationSaysWhereAnAgentSearchesAndWhatItMayUse() {
        String text =
                io.github.llm4j.loom.explain.ScriptExplainer.explain(
                        new LoomParser(
                                        new Lexer(
                                                        "budget { searches: 5 }\n"
                                                                + RESEARCH.replace(
                                                                        "year",
                                                                        "year  max_searches: 2"))
                                                .tokenize())
                                .parseScript(),
                        "w.loom",
                        "Main");
        assertThat(text)
                .contains(
                        "searches the web inside its model calls (only rbi.org.in, who.int), at most 2 searches a call")
                .contains("5 web searches");
    }
}
