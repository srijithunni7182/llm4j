package io.github.llm4j.engram.benchmark;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.engram.core.EngramEngine;
import io.github.llm4j.engram.core.InMemoryStore;
import io.github.llm4j.engram.core.LLMContextIntelligenceAgent;
import io.github.llm4j.engram.core.TemplateContextIntelligenceAgent;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.memory.MemoryEngine;
import io.github.llm4j.loom.memory.TranscriptAccumulationEngine;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Measures, rather than assumes, what Engram costs and saves. It drives the real engines (real
 * embeddings, real retrieval scoring, the real CIA prompts) through a multi-agent build where every
 * turn depends on a decision made in an earlier turn. Only the model is a stub, one that counts every
 * token sent and received (4 characters ≈ 1 token) and answers the CIA the way a model would: a few
 * extracted facts, a summary under 200 words, one introspection note.
 *
 * <p>For each mode it reports tokens spent on the agents, tokens spent on memory upkeep, calls, and
 * whether the decision a turn depends on actually reached the agent's prompt.
 */
class ContextCostBenchmarkTest {

    static final String[] AGENTS = {"Architect", "Backend", "Frontend", "Tester", "Reviewer"};
    static final String SYSTEM = "You are a senior engineer on a product team. ".repeat(18); // ≈200 tokens
    static final String[] AREAS = {"orders", "billing", "search", "profile", "checkout", "inventory", "shipping",
            "notifications", "analytics", "auth"};
    static final String[] PARTS = {"rate limiter", "cache", "retry policy", "schema", "pagination"};
    static final String[] WORDS = ("the service handles requests through a layered design where each module "
            + "owns its data and exposes a small interface tests cover edge cases like empty input timeouts and "
            + "partial failures we log structured events measure latency and keep the build green reviewers "
            + "check naming error handling and that the change stays backwards compatible with older clients")
            .split(" ");

    @Test
    void measure() throws Exception {
        StringBuilder report = new StringBuilder("# Engram context cost — measured\n\n");
        for (int turns : new int[] {10, 20, 50}) {
            report.append(section(turns));
        }
        Path out = Path.of("target", "context-cost-benchmark.md");
        Files.writeString(out, report);
        System.out.println(report);
    }

    private String section(int turns) {
        List<Row> rows = List.of(
                run("Loom default (shared transcript)", turns, new TranscriptAccumulationEngine(), false),
                run("Engram, LLM briefing", turns, new EngramEngine(new InMemoryStore(), new LLMContextIntelligenceAgent(stub)), false),
                run("Engram, template briefing", turns, new EngramEngine(new InMemoryStore(), new TemplateContextIntelligenceAgent()), false),
                run("No memory, facts passed as {variables}", turns, null, true));
        StringBuilder s = new StringBuilder("## " + turns + " turns (600-token outputs, 5 agents)\n\n");
        s.append("| Mode | Agent tokens | Memory upkeep tokens | Total tokens | Billed with prompt caching | LLM calls | Last agent prompt | Needed fact reached agent |\n");
        s.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Row r : rows) {
            s.append(String.format("| %s | %,d | %,d | %,d | %,d | %d | %,d | %d/%d |%n", r.mode, r.agentTokens, r.upkeepTokens,
                    r.agentTokens + r.upkeepTokens, r.billed(), r.calls, r.lastPrompt, r.recalled, r.needed));
        }
        return s.append('\n').toString();
    }

    // ── one mode ───────────────────────────────────────────────────────────

    record Row(String mode, long agentTokens, long upkeepTokens, int calls, long lastPrompt, int recalled, int needed,
               long cachedInput) {
        /** What the run is billed as, in tokens, when cached input costs 10% of fresh input. */
        long billed() {
            return agentTokens + upkeepTokens - Math.round(cachedInput * 0.9);
        }
    }

    private final CountingStub stub = new CountingStub();

    private Row run(String mode, int turns, MemoryEngine memory, boolean explicit) {
        stub.reset();
        long agentTokens = 0, lastPrompt = 0, cached = 0;
        java.util.Map<String, String> previous = new java.util.HashMap<>();
        int recalled = 0, needed = 0, agentCalls = 0;
        for (int t = 1; t <= turns; t++) {
            AgentDef agent = new AgentDef(AGENTS[(t - 1) % AGENTS.length]);
            int dep = t == 1 ? 0 : dependency(t);
            String task = "TURN " + t + ": " + (dep == 0 ? "Plan the first module."
                    : "Implement the " + component(dep) + " for this release, following the earlier decision about it.");
            String context;
            if (explicit) {
                context = dep == 0 ? task : task + "\nDecision: " + fact(dep);
            } else {
                context = memory.assembleContext(agent, task, null);
            }
            String prompt = SYSTEM + agent.getName() + "\n" + context;
            cached += tokens(prompt.substring(0, commonPrefix(prompt, previous.getOrDefault(agent.getName(), ""))));
            previous.put(agent.getName(), prompt);
            String output = output(t);
            agentTokens += tokens(prompt) + tokens(output);
            agentCalls++;
            lastPrompt = tokens(prompt);
            if (dep > 0) {
                needed++;
                if (prompt.contains(code(dep))) recalled++;
            }
            if (!explicit) {
                memory.storeOutcome(agent, task, AgentResult.builder().finalAnswer(output).completed(true).build(), null);
            }
        }
        return new Row(mode, agentTokens, stub.tokens, agentCalls + stub.calls, lastPrompt, recalled, needed,
                cached + stub.cached);
    }

    /** Providers cache a prompt's prefix; only the part after the shared prefix is billed in full. */
    static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length()), i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i < 1024 ? 0 : i; // caches only kick in past ~1k tokens' worth of prefix; be conservative
    }

    /** Each turn depends on one earlier decision: sometimes the last one, often an old one. */
    static int dependency(int t) {
        return t % 3 == 0 ? t - 1 : 1 + (t * 7) % (t - 1);
    }

    static String component(int t) {
        return AREAS[(t - 1) % AREAS.length] + " " + PARTS[((t - 1) / AREAS.length) % PARTS.length];
    }

    static String code(int t) {
        return "CFG-" + (1000 + t * 37);
    }

    static String fact(int t) {
        return "Decision: the " + component(t) + " uses setting " + code(t) + ".";
    }

    /** ≈600 tokens of plausible prose with the turn's decision in the middle. */
    static String output(int t) {
        Random r = new Random(t);
        StringBuilder text = new StringBuilder();
        while (text.length() < 1200) text.append(sentence(r));
        text.append(fact(t)).append(' ');
        while (text.length() < 2400) text.append(sentence(r));
        return text.toString();
    }

    static String sentence(Random r) {
        int n = 10 + r.nextInt(8);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append(i == 0 ? "" : " ").append(WORDS[r.nextInt(WORDS.length)]);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1) + ". ";
    }

    static long tokens(String text) {
        return text == null ? 0 : (text.length() + 3) / 4;
    }

    /** Answers the CIA's three prompts the way a model would, and counts everything. */
    static class CountingStub implements LLMClient {
        long tokens, cached;
        int calls;

        void reset() {
            tokens = 0;
            cached = 0;
            calls = 0;
        }

        @Override
        public LLMResponse chat(LLMRequest request) {
            String system = request.getMessages().stream().filter(m -> m.getRole() == Message.Role.SYSTEM)
                    .map(Message::getContent).collect(Collectors.joining());
            String user = request.getMessages().stream().filter(m -> m.getRole() == Message.Role.USER)
                    .map(Message::getContent).collect(Collectors.joining());
            String answer;
            if (system.contains("Memory Extraction")) {
                List<String> sentences = Arrays.stream(user.split("(?<=\\.) ")).map(String::trim).toList();
                List<String> facts = new ArrayList<>(sentences.stream().filter(s -> s.startsWith("Decision:")).toList());
                sentences.stream().filter(s -> s.length() > 40 && !s.startsWith("Decision:")).limit(3).forEach(facts::add);
                answer = facts.stream().map(f -> "[FACT] " + f).collect(Collectors.joining("\n"));
            } else if (system.contains("Context Summarizer")) {
                String memories = user.substring(user.indexOf("Memories:") + 9, user.lastIndexOf("Summarize the briefing."));
                String[] words = memories.replace("- ", "").trim().split("\\s+");
                answer = String.join(" ", Arrays.copyOf(words, Math.min(200, words.length)));
            } else if (system.contains("Introspector")) {
                answer = "[FACT] " + user.substring(0, Math.min(60, user.length())).replace('\n', ' ') + " went as briefed.";
            } else {
                throw new IllegalStateException("unexpected call");
            }
            calls++;
            tokens += tokens(system) + tokens(user) + tokens(answer);
            cached += tokens(system); // the fixed CIA instructions are the only reusable prefix
            return LLMResponse.builder().content(answer).build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            return Stream.of(chat(request));
        }
    }
}
