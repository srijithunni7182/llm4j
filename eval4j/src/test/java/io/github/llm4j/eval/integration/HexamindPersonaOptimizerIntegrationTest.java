package io.github.llm4j.eval.integration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.persona.AgentPersona;
import io.github.llm4j.agent.persona.PersonaLibrary;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.eval.criteria.Criteria;
import io.github.llm4j.eval.criteria.Criterion;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.dataset.EvalScenarios;
import io.github.llm4j.eval.judge.JudgeVerdict;
import io.github.llm4j.eval.judge.LlmJudgeCondition;
import io.github.llm4j.eval.optimize.Candidate;
import io.github.llm4j.eval.optimize.LlmCallCounter;
import io.github.llm4j.eval.optimize.OptimizationResult;
import io.github.llm4j.eval.optimize.OptimizerBudget;
import io.github.llm4j.eval.optimize.PromptConstraints;
import io.github.llm4j.eval.optimize.PromptOptimizer;
import io.github.llm4j.eval.optimize.Split;
import io.github.llm4j.eval.optimize.SystemUnderTest;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Optimizes two Hexamind Hub agent personas (examples/hexamind-hub) against golden datasets derived
 * from the personas' documented behavior (MEET_THE_TEAM.md and prompts.yaml).
 *
 * <ul>
 *   <li><b>Rahul</b>, the constructive skeptic: debunk premises with no evidence, but on real
 *       topics say why the idea might fail and how likely that is.
 *   <li><b>Casey</b>, the user advocate: speak for real people and accessibility, take a stance.
 *       The shipped code gives Casey the generic {@code customerSupport()} persona, which does not
 *       match the documented one, so the seed is the shipped prompt.
 * </ul>
 *
 * Harness limits, stated plainly: the real agents are ReAct agents with a web-search tool; here the
 * persona runs tool-less (the "MANDATORY web search" lines are dropped and the user message says no
 * tools are available), so what is optimized and measured is the persona text, not tool use. Hard
 * rules are deterministic checks (see the lesson from the QA study); an independent Opus rubric
 * judges persona fidelity and substance, to catch keyword stuffing. Opt-in via {@code
 * EVAL4J_ANTHROPIC_API_KEY}; writes a Markdown report.
 */
class HexamindPersonaOptimizerIntegrationTest {

    private static final Pattern DEBUNK =
            Pattern.compile(
                    "no evidence|cannot verify|can't verify|could not (?:find|verify)|couldn't"
                            + " (?:find|verify)|fabricat|does not exist|doesn't exist|not aware of"
                            + "|no record|unverif|fictional|not a real|no such|can't find|cannot"
                            + " find|nonexistent|non-existent|not recogni[sz]ed|unable to"
                            + " (?:find|verify)|no (?:credible|reliable|public)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern LIKELIHOOD =
            Pattern.compile(
                    "\\d{1,3}\\s?%|percent|likely|unlikely|probab|odds|chance|likelihood",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern FAILURE =
            Pattern.compile(
                    "fail|risk|flaw|counter-?example|downside|weak|break|problem|bottleneck",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern CLICHE =
            Pattern.compile(
                    "phased approach|holistic|proceed with caution|premature discussion",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern USERS =
            Pattern.compile(
                    "screen reader|accessib|blind|low[- ]vision|deaf|hearing|motor|keyboard|older"
                            + "|elderly|grandmother|grandparent|non-technical|novice|first-time"
                            + "|contrast|caption|cognitive|dyslex|wcag|disabilit|colou?r-?blind"
                            + "|visually impaired",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern STANCE =
            Pattern.compile(
                    "\\bveto|\\bblock\\b|\\bobject|\\breject|\\bapprove|\\bsupport (?:this|the)"
                            + "|\\bback this|do not ship|don't ship|\\bship it|\\bI recommend"
                            + "|\\bwe should (?:not|drop|keep|ship|add)|\\bnot ship",
                    Pattern.CASE_INSENSITIVE);

    // --- the agents under optimization -------------------------------------------------------

    private static String rahulSeed() {
        // Mirrors AgentConfiguration.createRahulAgent, minus the web-search and clock constraints.
        return AgentPersona.builder()
                .name("Rahul")
                .role("Cynical Commoner & Adversarial Source Researcher")
                .expertise(
                        "Real-world news, Alternate Viewpoints, Source Verification, Logical fallacies")
                .tone("Cynical, probing, and highly analytical")
                .addConstraint("PRONG: Focal point for Adversarial Research & Source Verification.")
                .addConstraint(
                        "ADAPTIVITY: Verify specific citations/links from others. Actively search for"
                                + " alternate views or contradictory data for every 'fact' presented.")
                .addConstraint(
                        "While you are cynical, DO NOT dismiss Sasha's futurist predictions outright."
                                + " Instead, critique the *path* to that future or the *probability*,"
                                + " not the possibility itself.")
                .addConstraint(
                        "Always look for counter-examples and data points that challenge the group's"
                                + " consensus to break echo chambers.")
                .addCustomAttribute("pessimismLevel", "high")
                .description(
                        "A cynical observer who serves as the group's reality check, verifying"
                                + " sources and hunting for contradictory evidence to prevent"
                                + " collective hallucinations.")
                .build()
                .toSystemPromptAddition();
    }

    private static String caseySeed() {
        // Mirrors AgentConfiguration.createAgent for Casey: the library's customerSupport()
        // persona plus the shared rigor constraint (minus web search and clock).
        AgentPersona library = PersonaLibrary.customerSupport();
        AgentPersona.Builder builder =
                AgentPersona.builder()
                        .name(library.getName())
                        .role(library.getRole())
                        .expertise(library.getExpertise())
                        .tone(library.getTone())
                        .description(library.getDescription());
        library.getConstraints().forEach(builder::addConstraint);
        return builder.addConstraint(
                        "Avoid generic phrases like 'phased approach' or 'proceed with caution'. Be"
                                + " specific and data-driven.")
                .build()
                .toSystemPromptAddition();
    }

    /** prompts.yaml {@code agent_analyze}, with the web-search mandate replaced (no tools here). */
    private static String userMessage(String role, EvalScenario scenario) {
        return "Analyze this problem from your perspective as a "
                + role
                + ": "
                + scenario.input()
                + "\n\nNo tools are available in this session: answer from your own knowledge, and"
                + " say plainly if you cannot verify something.\n\n"
                + "INFORMATION DIVERSITY REQUIREMENT: Identify the *type* of source you are citing"
                + " (e.g., [Journal], [News], [Social Media], [Whitepaper]). Prioritize your assigned"
                + " PRONG while acknowledging findings from other PRONGS. Avoid corporate clichés and"
                + " provide empirical, data-backed insights in 2-4 sentences.";
    }

    // --- deterministic checks (computed outside any judge) -----------------------------------

    private static int sentences(String text) {
        String stripped = text.replaceAll("(?m)^\\s*[-*•]\\s*", "").trim();
        return stripped.isEmpty() ? 0 : stripped.split("(?<=[.!?])\\s+").length;
    }

    /** Failed-rule descriptions for a Rahul output; empty means every rule holds. */
    static List<String> rahulFailures(EvalScenario scenario, String output) {
        List<String> failed = new ArrayList<>();
        boolean fabricated = "FABRICATED".equals(scenario.expectedOutput());
        boolean debunked = DEBUNK.matcher(output).find();
        if (fabricated && !debunked) {
            failed.add(
                    "the question rests on a term that does not exist, and the reply never says it"
                            + " cannot be verified or does not exist");
        }
        if (!fabricated && debunked) {
            failed.add(
                    "the topic is real, but the reply claims it cannot be verified or does not"
                            + " exist");
        }
        if (!fabricated && !(LIKELIHOOD.matcher(output).find() && FAILURE.matcher(output).find())) {
            failed.add(
                    "a real topic needs a specific way it could fail AND how likely that failure is");
        }
        if (CLICHE.matcher(output).find()) {
            failed.add("uses a banned corporate cliché");
        }
        if (sentences(output) > 5) {
            failed.add("too long: " + sentences(output) + " sentences (the brief asks for 2-4)");
        }
        return failed;
    }

    /** Failed-rule descriptions for a Casey output; empty means every rule holds. */
    static List<String> caseyFailures(EvalScenario scenario, String output) {
        List<String> failed = new ArrayList<>();
        if (!USERS.matcher(output).find()) {
            failed.add(
                    "never names who is affected or an accessibility need (for example older users,"
                            + " blind users, keyboard-only users)");
        }
        if (!STANCE.matcher(output).find()) {
            failed.add("takes no explicit stance (it neither approves nor objects/vetoes)");
        }
        if (CLICHE.matcher(output).find()) {
            failed.add("uses a banned corporate cliché");
        }
        if (sentences(output) > 5) {
            failed.add("too long: " + sentences(output) + " sentences (the brief asks for 2-4)");
        }
        return failed;
    }

    private interface Rules {
        List<String> failures(EvalScenario scenario, String output);
    }

    private static final int RULE_COUNT = 4;

    private static JudgeVerdict score(Rules rules, EvalScenario scenario, Object output) {
        List<String> failed = rules.failures(scenario, String.valueOf(output));
        double score = Math.max(0, 1.0 - failed.size() / (double) RULE_COUNT);
        return new JudgeVerdict(
                score, failed.isEmpty() ? "all rules hold" : String.join("; ", failed));
    }

    // --- agent definitions -------------------------------------------------------------------

    private record Agent(
            String name,
            String role,
            String seed,
            String description,
            Rules rules,
            String fidelityRubric,
            String goldenResource) {}

    private static List<Agent> agents() {
        return List.of(
                new Agent(
                        "rahul",
                        "Cynical Commoner & Adversarial Source Researcher",
                        rahulSeed(),
                        "System prompt of Rahul, the 'Constructive Skeptic' of a six-agent debate. He"
                                + " trusts no one and hunts for contradictory evidence, but he is"
                                + " constructive: when a term in the question has no evidence behind it he"
                                + " says so plainly and does not entertain the premise; when the topic is"
                                + " real he does not just say no, he says why it might fail and how likely"
                                + " that failure is. He answers in 2-4 short sentences.",
                        HexamindPersonaOptimizerIntegrationTest::rahulFailures,
                        "The reply reads as a constructive skeptic: where a premise cannot be verified it"
                                + " says so plainly instead of playing along; otherwise it names a specific,"
                                + " plausible way the idea could fail and judges how likely that is. It is"
                                + " specific, not boilerplate, and not just a list of keywords.",
                        "hexamind/rahul-golden.yaml"),
                new Agent(
                        "casey",
                        "customer service representative",
                        caseySeed(),
                        "System prompt of Casey, the 'Advocate' of a six-agent debate: Customer Experience"
                                + " & Accessibility Lead. She talks about people, not systems or markets:"
                                + " Will a grandmother understand this interface? Is it accessible to the"
                                + " blind? She fights for the user and vetoes anything annoying or"
                                + " exclusionary, however revolutionary, and approves what serves people."
                                + " She answers in 2-4 short sentences.",
                        HexamindPersonaOptimizerIntegrationTest::caseyFailures,
                        "The reply reads as a customer-experience and accessibility advocate: it is"
                                + " concrete about which real people are helped or harmed (for example"
                                + " older, blind or keyboard-only users), speaks plainly, and takes a clear"
                                + " stance on the decision. It is specific, not boilerplate, and not just a"
                                + " list of keywords.",
                        "hexamind/casey-golden.yaml"));
    }

    // --- plumbing ----------------------------------------------------------------------------

    private static LLMClient client(String key, String model) {
        return new DefaultLLMClient(
                new AnthropicProvider(LLMConfig.builder().apiKey(key).defaultModel(model).build()));
    }

    private static SystemUnderTest system(LLMClient model, String role) {
        return (candidate, scenario) ->
                model.chat(
                                LLMRequest.builder()
                                        .addSystemMessage(candidate.get("persona"))
                                        .addUserMessage(userMessage(role, scenario))
                                        .temperature(0.3)
                                        .build())
                        .getContent();
    }

    private static double groundTruth(
            Agent agent,
            SystemUnderTest system,
            Candidate candidate,
            List<EvalScenario> scenarios) {
        return scenarios.stream()
                .mapToDouble(
                        s ->
                                agent.rules()
                                                .failures(s, system.run(candidate, s).toString())
                                                .isEmpty()
                                        ? 1
                                        : 0)
                .average()
                .orElse(0);
    }

    private static double fidelity(
            Agent agent,
            SystemUnderTest system,
            Candidate candidate,
            List<EvalScenario> scenarios,
            LLMClient j2) {
        var judge =
                LlmJudgeCondition.llmJudged("persona-fidelity")
                        .criteria(agent.fidelityRubric())
                        .judge(j2)
                        .threshold(0.7)
                        .build();
        return scenarios.stream()
                .mapToDouble(s -> judge.evaluate(system.run(candidate, s).toString()).score())
                .average()
                .orElse(0);
    }

    @Test
    void optimizeHexamindPersonas() throws Exception {
        String key = System.getenv("EVAL4J_ANTHROPIC_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "EVAL4J_ANTHROPIC_API_KEY not set");
        var env = System.getenv();
        int seeds = Integer.parseInt(env.getOrDefault("EVAL4J_OPTIMIZER_SEEDS", "2"));
        int maxRollouts = Integer.parseInt(env.getOrDefault("EVAL4J_OPTIMIZER_ROLLOUTS", "250"));
        long maxCalls = Long.parseLong(env.getOrDefault("EVAL4J_STUDY_MAX_LLM_CALLS", "1500"));
        List<String> wanted =
                List.of(env.getOrDefault("EVAL4J_STUDY_AGENTS", "rahul,casey").split(","));
        Path out = Path.of(env.getOrDefault("EVAL4J_STUDY_OUT", "target/hexamind-optimizer.md"));
        LLMClient systemModel =
                client(key, env.getOrDefault("EVAL4J_SYSTEM_MODEL", "claude-haiku-4-5-20251001"));
        LLMClient rewriter =
                client(key, env.getOrDefault("EVAL4J_REWRITER_MODEL", "claude-sonnet-5-5"));
        LLMClient j2 = client(key, env.getOrDefault("EVAL4J_J2_MODEL", "claude-opus-5-5"));

        StringBuilder md = new StringBuilder("# Hexamind persona optimization\n\n");
        md.append(
                "_Tool-less harness: the agents' web search is not available, so the persona text is"
                        + " what is optimized and measured. Golden sets are synthetic and derived from the"
                        + " documented personas. GT = deterministic rules computed outside any judge;"
                        + " fidelity = independent Opus rubric on the same test outputs. Budget per run: "
                        + maxRollouts
                        + " rollouts._\n\n");

        for (Agent agent : agents()) {
            if (!wanted.contains(agent.name())) {
                continue;
            }
            List<EvalScenario> all = EvalScenarios.fromYamlResource(agent.goldenResource());
            md.append("## ")
                    .append(agent.name())
                    .append(" (")
                    .append(all.size())
                    .append(" golden scenarios)\n\n");
            md.append(
                    "| seed | GT test: seed → best | fidelity (Opus): seed → best | train/val/test | `generalized` | stop | rounds | rollouts | LLM calls |\n|---|---|---|---|---|---|---|---|---|\n");
            StringBuilder details = new StringBuilder();
            for (int s = 1; s <= seeds; s++) {
                LlmCallCounter systemCounter = LlmCallCounter.wrap(systemModel);
                SystemUnderTest system = system(systemCounter, agent.role());
                Criterion rules =
                        Criteria.perScenario(
                                "persona-rules",
                                sc ->
                                        Criteria.judged(
                                                "persona-rules",
                                                output ->
                                                        score(agent.rules()::failures, sc, output),
                                                0.99));
                var split = Split.ratios(0.4, 0.3, 0.3).seed(s);
                OptimizationResult result =
                        PromptOptimizer.builder()
                                .seed(Candidate.of("persona", agent.seed()))
                                .system(system)
                                .criteria(List.of(rules))
                                .scenarios(all)
                                .split(split)
                                .rewriter(rewriter)
                                .parameterDescription("persona", agent.description())
                                .constraints(PromptConstraints.builder().maxChars(3500).build())
                                .budget(
                                        OptimizerBudget.builder()
                                                .maxRollouts(maxRollouts)
                                                .maxLlmCalls(maxCalls)
                                                .build())
                                .targetValidationMean(0.95)
                                .parallelism(4)
                                .trackCalls(systemCounter)
                                .randomSeed(s)
                                .acknowledgeSideEffects()
                                .build()
                                .run();
                var data = split.apply(all);
                SystemUnderTest plain = system(systemModel, agent.role());
                Candidate seedCandidate = result.seed();
                Candidate best = result.best();
                md.append(
                        String.format(
                                Locale.ROOT,
                                "| %d | %.2f → **%.2f** | %.2f → %.2f | %d/%d/%d | %s | %s | %d | %d | %d |%n",
                                s,
                                groundTruth(agent, plain, seedCandidate, data.test()),
                                groundTruth(agent, plain, best, data.test()),
                                fidelity(agent, plain, seedCandidate, data.test(), j2),
                                fidelity(agent, plain, best, data.test(), j2),
                                data.train().size(),
                                data.validation().size(),
                                data.test().size(),
                                result.generalized(),
                                result.stopReason(),
                                result.trace().size(),
                                result.cost().rollouts(),
                                result.cost().trackedLlmCalls() + result.cost().rewriterCalls()));
                EvalScenario sample = data.test().get(0);
                details.append("### ")
                        .append(agent.name())
                        .append(" seed ")
                        .append(s)
                        .append("\n\n**Verdict:** ")
                        .append(String.join("; ", result.verdict().reasons()))
                        .append("\n\n**Sample input** (`")
                        .append(sample.name())
                        .append("`): ")
                        .append(sample.input())
                        .append("\n\n**Seed reply:**\n\n> ")
                        .append(plain.run(seedCandidate, sample).toString().replace("\n", "\n> "))
                        .append("\n\n**Optimized reply:**\n\n> ")
                        .append(plain.run(best, sample).toString().replace("\n", "\n> "))
                        .append("\n\n<details><summary>Prompt diff</summary>\n\n```diff\n")
                        .append(result.toPatch().unifiedDiff())
                        .append("\n```\n\n</details>\n\n");
                Files.createDirectories(out.toAbsolutePath().getParent());
                Files.writeString(out, md + "\n" + details);
            }
            md.append("\n").append(details);
        }
        Files.writeString(out, md.toString());
        System.out.println(md);
    }
}
