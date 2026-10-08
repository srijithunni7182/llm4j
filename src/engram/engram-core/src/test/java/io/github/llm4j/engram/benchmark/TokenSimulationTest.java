package io.github.llm4j.engram.benchmark;

import org.junit.jupiter.api.Test;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 4.1 Simulation: Mathematical Proof of Token Efficiency
 * 
 * Compares O(N^2) growth of Transcript mode vs O(N) stability of Engram.
 * Simulates 20 turns of a development workflow.
 */
class TokenSimulationTest {

    private static final int TOTAL_TURNS = 20;
    private static final int SYSTEM_PROMPT_TOKENS = 200;
    private static final int USER_PROMPT_TOKENS = 100;
    private static final int AVG_RESPONSE_TOKENS = 600;
    private static final int ENGRAM_BRIEFING_CAP = 800; // Smart synthesis limit
    private static final int CIA_OVERHEAD_PER_TURN = 400; // Extra calls for extraction/synthesis

    @Test
    void runSimulation() throws Exception {
        List<DataPoint> transcriptStats = new ArrayList<>();
        List<DataPoint> engramStats = new ArrayList<>();

        // --- Run A: Transcript Accumulation (Naive) ---
        long accumulatedHistory = 0;
        for (int turn = 1; turn <= TOTAL_TURNS; turn++) {
            long promptSize = SYSTEM_PROMPT_TOKENS + accumulatedHistory + USER_PROMPT_TOKENS;
            transcriptStats.add(new DataPoint(turn, promptSize, AVG_RESPONSE_TOKENS));
            
            // History grows by the full turn (prompt + response)
            accumulatedHistory += (USER_PROMPT_TOKENS + AVG_RESPONSE_TOKENS);
        }

        // --- Run B: Engram Engine (Smart) ---
        for (int turn = 1; turn <= TOTAL_TURNS; turn++) {
            // Engram prompt size is STABLE: System + Synthesized Briefing + Current User Prompt
            // We assume a small growth in briefing complexity up to a cap
            long briefingSize = Math.min(ENGRAM_BRIEFING_CAP, 200 + (turn * 30)); 
            long promptSize = SYSTEM_PROMPT_TOKENS + briefingSize + USER_PROMPT_TOKENS;
            
            // Total tokens for Engram = Agent Prompt + Agent Response + CIA Overhead
            engramStats.add(new DataPoint(turn, promptSize + CIA_OVERHEAD_PER_TURN, AVG_RESPONSE_TOKENS));
        }

        generateMarkdownReport(transcriptStats, engramStats);
    }

    private void generateMarkdownReport(List<DataPoint> trans, List<DataPoint> engram) throws Exception {
        try (PrintWriter out = new PrintWriter(new FileWriter("simulation-results.md"))) {
            out.println("# 📊 Token Growth Simulation — Engram vs Transcript");
            out.println("> Mathematical projection of 20-turn workflow | 600 tokens/turn response");
            out.println("\n## Efficiency Curve\n");
            out.println("| Turn | Transcript Prompt | Engram Prompt | Savings |");
            out.println("|------|-------------------|---------------|---------|");

            long totalTrans = 0;
            long totalEngram = 0;

            for (int i = 0; i < TOTAL_TURNS; i++) {
                DataPoint t = trans.get(i);
                DataPoint e = engram.get(i);
                
                long savings = t.promptTokens - e.promptTokens;
                String savingsPct = String.format("%.1f%%", (savings * 100.0 / t.promptTokens));
                
                out.printf("| %d | %,d | %,d | **%s** |%n", i + 1, t.promptTokens, e.promptTokens, savingsPct);
                
                totalTrans += (t.promptTokens + t.responseTokens);
                totalEngram += (e.promptTokens + e.responseTokens);
            }

            out.println("\n## Cumulative Totals (Over 20 Turns)\n");
            out.println("| Mode | Total Tokens Consumed | Status |");
            out.println("|------|-----------------------|--------|");
            out.printf("| Transcript Accumulation | %,d | O(N²) Growth |%n", totalTrans);
            out.printf("| Engram Engine | %,d | O(N) Stability |%n", totalEngram);
            out.println();
            
            double totalSavings = (totalTrans - totalEngram) * 100.0 / totalTrans;
            out.printf("### 🏆 Total Token Savings: **%.1f%%**%n", totalSavings);
            
            out.println("\n## The \"Wall of Context\"\n");
            out.println("In **Transcript Mode**, Turn 20 requires a prompt of **" + trans.get(19).promptTokens + " tokens**.");
            out.println("In **Engram Mode**, Turn 20 stays lean at **" + engram.get(19).promptTokens + " tokens**.");
            out.println("\nAs workflows scale, Transcript mode inevitably hits context limits or becomes prohibitively expensive. Engram maintains a constant reasoning window regardless of turn count.");
        }
    }

    private record DataPoint(int turn, long promptTokens, long responseTokens) {}
}
