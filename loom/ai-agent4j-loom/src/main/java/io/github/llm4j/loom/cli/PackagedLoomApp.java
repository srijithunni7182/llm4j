package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.*;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The standard entry point for a packaged Loom workflow JAR.
 */
public class PackagedLoomApp {

    public static void main(String[] args) throws Exception {
        System.out.println("🧵 Loom Packaged Workflow Starting...");

        // 1. Load resources from classpath
        String scriptContent;
        try (InputStream is = PackagedLoomApp.class.getResourceAsStream("/app.loom")) {
            if (is == null) throw new IllegalStateException("app.loom not found in classpath.");
            scriptContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        ToolRegistry registry = new ToolRegistry();
        try (InputStream is = PackagedLoomApp.class.getResourceAsStream("/app.loot")) {
            if (is != null) {
                // LootLoader needs a path, but we can implement a stream loader or write to temp
                java.nio.file.Path tempLoot = java.nio.file.Files.createTempFile("app", ".loot");
                java.nio.file.Files.write(tempLoot, is.readAllBytes());
                new LootLoader().loadIntoRegistry(tempLoot.toAbsolutePath().toString(), registry);
                System.out.println("🛠️  Loaded tools from embedded registry.");
            }
        }

        // 2. Parse
        Lexer lexer = new Lexer(scriptContent);
        LoomParser parser = new LoomParser(lexer.tokenize());
        LoomScript script = parser.parseScript();

        // 3. Setup Executor
        LLMClientFactory clientFactory = new DefaultLLMClientFactory();

        HarnessExecutor executor = new HarnessExecutor(script, registry, clientFactory);
        executor.setHumanInterface(new ConsoleHumanInterface());
        executor.initialize();

        // 4. Input handling (simplistic CLI args parsing: key=value)
        Map<String, String> inputs = new HashMap<>();
        for (String arg : args) {
            if (arg.contains("=")) {
                String[] parts = arg.split("=", 2);
                inputs.put(parts[0], parts[1]);
            }
        }

        String workflowName = System.getProperty("loom.workflow", "Main");

        try {
            executor.executeWorkflow(workflowName, inputs);
        } finally {
            executor.shutdown();
        }
    }
}
