package io.github.llm4j.getviral;

import io.github.llm4j.getviral.app.GetViralWebApp;
import io.github.llm4j.getviral.config.GetViralConfig;
import io.github.llm4j.getviral.engine.GetViralEngine;
import io.github.llm4j.getviral.web.ConsoleStudio;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;

/**
 * GetViral — one idea in, a ready-to-post pack for X, Instagram Reels and YouTube out.
 *
 * <pre>
 *   java -jar getviral.jar                      # hosted web app on http://localhost:7070
 *   java -jar getviral.jar --cli "your idea"    # single-user run in the terminal (no accounts)
 * </pre>
 */
public final class GetViralApp {

    private GetViralApp() { }

    public static void main(String[] args) {
        // Loom's parallel blocks run on the common pool; small containers would otherwise serialise them.
        System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", "12");
        System.setProperty("java.awt.headless", "true");
        java.util.logging.LogManager.getLogManager().reset(); // Loom's JUL chatter; the app logs via SLF4J

        if (args.length > 0 && args[0].equals("--cli")) {
            GetViralConfig config = GetViralConfig.fromEnvironment();
            long pace = config.mode() == GetViralConfig.Mode.DEMO ? 450 : 0;
            String idea = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).strip();
            new ConsoleStudio(new GetViralEngine(config, pace))
                    .run(idea.isEmpty() ? "a 2-minute morning routine for busy students" : idea);
            return;
        }
        SpringApplication.run(GetViralWebApp.class, args);
    }
}
