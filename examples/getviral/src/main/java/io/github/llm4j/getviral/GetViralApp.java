package io.github.llm4j.getviral;

import io.github.llm4j.getviral.config.GetViralConfig;
import io.github.llm4j.getviral.engine.GetViralEngine;
import io.github.llm4j.getviral.web.ConsoleStudio;
import io.github.llm4j.getviral.web.StudioServer;
import java.util.Arrays;

/**
 * GetViral — one idea in, a ready-to-post pack for X, Instagram Reels and YouTube out.
 *
 * <pre>
 *   java -jar getviral.jar                      # web studio on http://localhost:7070
 *   java -jar getviral.jar --cli "your idea"    # run in the terminal
 * </pre>
 */
public final class GetViralApp {

    private GetViralApp() { }

    public static void main(String[] args) throws Exception {
        // Loom's parallel blocks run on the common pool; small containers would otherwise serialise them.
        System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", "12");
        System.setProperty("java.awt.headless", "true");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel",
                System.getProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn"));
        java.util.logging.LogManager.getLogManager().reset();

        GetViralConfig config = GetViralConfig.fromEnvironment();
        long pace = config.mode() == GetViralConfig.Mode.DEMO ? 450 : 0;
        GetViralEngine engine = new GetViralEngine(config, pace);

        if (args.length > 0 && args[0].equals("--cli")) {
            String idea = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).strip();
            new ConsoleStudio(engine).run(idea.isEmpty() ? "a 2-minute morning routine for busy students" : idea);
            return;
        }
        new StudioServer(engine).start();
    }
}
