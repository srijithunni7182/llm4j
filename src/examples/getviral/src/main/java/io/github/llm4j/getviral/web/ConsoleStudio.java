package io.github.llm4j.getviral.web;

import io.github.llm4j.getviral.engine.GetViralEngine;
import io.github.llm4j.getviral.studio.StudioRun;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** GetViral in the terminal: live agent feed, interactive hook pick and publish approval. */
public class ConsoleStudio {

    private static final String RESET = "\u001B[0m", DIM = "\u001B[2m", BOLD = "\u001B[1m",
            PINK = "\u001B[38;5;205m", ORANGE = "\u001B[38;5;214m", CYAN = "\u001B[38;5;87m",
            GREEN = "\u001B[38;5;114m", RED = "\u001B[38;5;203m";

    private final GetViralEngine engine;
    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    public ConsoleStudio(GetViralEngine engine) {
        this.engine = engine;
    }

    public void run(String idea) {
        GetViralEngine.Brief brief = new GetViralEngine.Brief(idea, env("GETVIRAL_HANDLE", "creator"),
                env("GETVIRAL_NICHE", "lifestyle"), env("GETVIRAL_TONE", "warm and witty"), env("GETVIRAL_REGION", "US"), List.of());
        StudioRun run = new StudioRun(brief.toMap());
        run.autopilot(new StudioRun.Autopilot() {
            @Override
            public String answer(String kind, String message, List<String> options) {
                if (kind.equals("hook")) {
                    System.out.println("\n" + BOLD + PINK + "✦ Pick your hook" + RESET);
                    for (int i = 0; i < options.size(); i++) System.out.println("  " + (i + 1) + ") " + options.get(i));
                    String line = read("  number (enter = 1): ");
                    try {
                        return options.get(Math.max(0, Integer.parseInt(line.strip()) - 1));
                    } catch (RuntimeException e) {
                        return options.isEmpty() ? "" : options.get(0);
                    }
                }
                if (kind.equals("publish")) {
                    String url = read("\n" + BOLD + ORANGE + "✦ Publish the Reel?" + RESET + " public https video URL (enter = skip): ");
                    return url.isBlank() ? "skip" : url.strip();
                }
                return read(message + " ");
            }

            @Override
            public boolean approve(String tool, Map<String, Object> args) {
                System.out.println("\n" + BOLD + RED + "⚠ Approval needed: " + tool + RESET + "\n  " + args);
                return read("  approve? [y/N]: ").strip().equalsIgnoreCase("y");
            }
        });
        run.subscribe(event -> {
            try {
                print(event);
            } catch (RuntimeException e) {
                System.out.println(DIM + "  (could not render " + event.get("type") + ": " + e + ")" + RESET);
            }
        });
        GetViralEngine.Outcome outcome = engine.run(run, brief);
        if (outcome.status() == StudioRun.Status.DONE) {
            System.out.println("\n" + PackMarkdown.render(run));
        }
    }

    private static void print(Map<String, Object> event) {
        Map<?, ?> d = (Map<?, ?>) event.get("data");
        switch (String.valueOf(event.get("type"))) {
            case "run_started" -> System.out.println(BOLD + PINK + "\n✦ GetViral" + RESET + DIM + "  " + d.get("model")
                    + " · " + d.get("embeddings") + RESET);
            case "prompt" -> System.out.println(CYAN + "  ✎ Showrunner wrote " + d.get("agent") + " prompt v" + d.get("version") + RESET);
            case "agent_start" -> System.out.println(BOLD + "▶ " + d.get("agent") + RESET);
            case "action" -> System.out.println(DIM + "    ↳ " + d.get("tool") + " " + d.get("input") + RESET);
            case "api" -> System.out.println(DIM + "      " + (Boolean.TRUE.equals(d.get("live")) ? GREEN + "● live " : ORANGE + "○ sample ")
                    + d.get("host") + " " + d.get("ms") + "ms" + RESET);
            case "agent_done" -> System.out.println(GREEN + "  ✓ " + d.get("agent") + RESET);
            case "memory_recall" -> {
                if (Boolean.TRUE.equals(d.get("recalled"))) System.out.println(CYAN + "  🧠 Engram briefed " + d.get("agent") + RESET);
            }
            case "quality" -> {
                System.out.println(BOLD + "\n✦ Quality gate (eval4j)" + RESET);
                for (Object b : (List<?>) d.get("badges")) {
                    Map<?, ?> badge = (Map<?, ?>) b;
                    System.out.printf("  %s %-45s %.2f%n", Boolean.TRUE.equals(badge.get("passed")) ? GREEN + "✓" + RESET : RED + "✗" + RESET,
                            badge.get("name"), ((Number) badge.get("score")).doubleValue());
                }
            }
            case "blocked" -> System.out.println(RED + "⛔ " + d.get("notice") + RESET);
            case "error" -> System.out.println(RED + "✗ " + d.get("message") + RESET);
            default -> { }
        }
    }

    private String read(String prompt) {
        System.out.print(prompt);
        try {
            String line = in.readLine();
            return line == null ? "" : line;
        } catch (Exception e) {
            return "";
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
