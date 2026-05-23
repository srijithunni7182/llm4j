package io.github.llm4j.nirmaan.agent;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.nirmaan.model.ProjectContext;

import jakarta.annotation.PostConstruct;

public abstract class BaseNirmaanAgent implements NirmaanAgent {

    protected LLMClient llmClient;

    @org.springframework.beans.factory.annotation.Autowired
    protected io.github.llm4j.nirmaan.service.KnowledgeService knowledgeService;

    @PostConstruct
    public void init() {
        String apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null) {
            System.err.println("GOOGLE_API_KEY environment variable is missing! Using 'DUMMY_KEY' (Validation Mode).");
            apiKey = "DUMMY_KEY";
        }

        this.llmClient = createLLMClient(apiKey);
    }

    private LLMClient createLLMClient(String apiKey) {
        String model = null;
        try {
            LLMConfig tempConfig = LLMConfig.builder().apiKey(apiKey).build();
            GoogleProvider tempProvider = new GoogleProvider(tempConfig);
            String discoveredModel = tempProvider.getFirstAvailableModel();

            if (discoveredModel != null) {
                model = discoveredModel;
                System.out.println(String.format("%s (%s) found model: %s", getName(), getRole(), model));
            } else {
                model = "gemini-3.5-flash";
                System.out.println(String.format("%s could not discover models, using fallback: %s", getName(), model));
            }
        } catch (Exception e) {
            model = "gemini-3.5-flash";
            System.out.println(String.format("%s model discovery failed: %s. Using fallback: %s", getName(),
                    e.getMessage(), model));
        }

        LLMConfig config = LLMConfig.builder()
                .apiKey(apiKey)
                .defaultModel(model)
                .build();

        return new DefaultLLMClient(new GoogleProvider(config));

    }

    protected String chatWithTools(ProjectContext context, String prompt) {
        int maxTurns = 3;
        StringBuilder chatHistory = new StringBuilder(prompt);
        String lastResponse = "";

        for (int i = 0; i < maxTurns; i++) {
            try {
                // 1. Call LLM
                io.github.llm4j.model.LLMRequest request = io.github.llm4j.model.LLMRequest.builder()
                        .addUserMessage(chatHistory.toString())
                        .temperature(0.2) // Low temp for tool use
                        .build();

                io.github.llm4j.model.LLMResponse response = llmClient.chat(request);
                lastResponse = response.getContent();

                // 2. Check for [SEARCH: ...]
                java.util.regex.Pattern searchPattern = java.util.regex.Pattern.compile("\\[SEARCH: (.*?)\\]");
                java.util.regex.Matcher matcher = searchPattern.matcher(lastResponse);

                if (matcher.find()) {
                    String query = matcher.group(1);
                    context.log(getName(), "Searching (Hybrid): " + query);

                    // Execute Search
                    java.util.List<String> results = knowledgeService.hybridSearch(query);
                    String searchBlock = "\n\n[SEARCH_RESULTS]\n" + String.join("\n---\n", results)
                            + "\n[/SEARCH_RESULTS]\n\n";

                    // Append to history and continue loop
                    chatHistory.append("\nAssistant: ").append(lastResponse);
                    chatHistory.append("\nSystem: ").append(searchBlock);
                    chatHistory.append("Please verify the info and provide the Final Answer (or search again).");

                } else {
                    // No search needed, return final response
                    return lastResponse;
                }

            } catch (Exception e) {
                context.log(getName(), "Error in Tool Loop: " + e.getMessage());
                return lastResponse; // Return what we have
            }
        }
        return lastResponse;
    }

    protected void logThought(ProjectContext context, String thought) {
        context.log(getName(), "[Thought] " + thought);
    }

    protected String readCurrentCode(ProjectContext context) {
        StringBuilder currentCode = new StringBuilder();
        try {
            if (java.nio.file.Files.exists(context.getSandboxPath())) {
                java.nio.file.Files.walk(context.getSandboxPath())
                        .filter(p -> java.nio.file.Files.isRegularFile(p))
                        .forEach(p -> {
                            try {
                                String relativePath = context.getSandboxPath().relativize(p).toString();
                                // Skip hidden files, logs, and target directory
                                if (relativePath.contains(".git") || relativePath.endsWith(".log")
                                        || relativePath.startsWith("target"))
                                    return;

                                currentCode.append("\n--- FILE: ").append(relativePath).append(" ---\n");
                                currentCode.append(java.nio.file.Files.readString(p));
                            } catch (Exception e) {
                                // Ignore read errors
                            }
                        });
            }
        } catch (Exception e) {
            context.log(getName(), "Error reading codebase: " + e.getMessage());
        }
        return currentCode.toString();
    }

    protected String readSmartContext(ProjectContext context, String errorLog) {
        StringBuilder smartContext = new StringBuilder();
        java.util.Set<java.nio.file.Path> processedFiles = new java.util.HashSet<>();

        try {
            // 1. Regex for Java Stack Traces: at com.pkg.Class.method(File.java:123)
            // Group 2: File.java, Group 3: Line Number
            java.util.regex.Pattern stackTracePattern = java.util.regex.Pattern
                    .compile("at\\s+([a-zA-Z0-9_.$]+)\\(([a-zA-Z0-9_]+\\.java):(\\d+)\\)");
            java.util.regex.Matcher stackMatcher = stackTracePattern.matcher(errorLog);

            while (stackMatcher.find()) {
                String fileName = stackMatcher.group(2);
                int lineNumber = Integer.parseInt(stackMatcher.group(3));
                appendFileSnippet(context, smartContext, processedFiles, fileName, lineNumber);
            }

            // 2. Regex for Compilation Errors: /path/to/File.java:123: error: ...
            // Group 1: File Name/Path, Group 2: Line Number
            java.util.regex.Pattern compileErrorPattern = java.util.regex.Pattern
                    .compile("([a-zA-Z0-9_/-]+\\.java):(\\d+): error");
            java.util.regex.Matcher compileMatcher = compileErrorPattern.matcher(errorLog);

            while (compileMatcher.find()) {
                String fileName = compileMatcher.group(1);
                // Extract just basename if it's a path
                if (fileName.contains("/")) {
                    fileName = fileName.substring(fileName.lastIndexOf("/") + 1);
                }
                int lineNumber = Integer.parseInt(compileMatcher.group(2));
                appendFileSnippet(context, smartContext, processedFiles, fileName, lineNumber);
            }

            // 3. Identify Failing Test Class to read full content
            // Simple heuristic: match "Running com.pkg.TestClass" if followed by failure?
            // Or just read if referenced in stack trace?
            // Let's assume stack trace logic covers specific lines, but we might want the
            // FULL
            // test file.
            // If a file ends in 'Test.java' and is in the stack trace, read the WHOLE file.
            // (Implemented in appendFileSnippet logic)

            if (smartContext.length() == 0) {
                // Fallback: If no patterns match, read everything (safe mode)
                return readCurrentCode(context);
            }

            // --- ENHANCEMENT: Always include Build Files for Context ---
            // This allows the agent to check dependencies even if the error is in a Java
            // file.
            String[] buildFiles = { "pom.xml", "build.gradle", "package.json", "requirements.txt" };
            for (String buildFile : buildFiles) {
                // Only add if not already processed (unlikely, unless error was IN the build
                // file)
                appendFileSnippet(context, smartContext, processedFiles, buildFile, -1);
            }

        } catch (Exception e) {
            context.log(getName(), "Smart Context Error: " + e.getMessage());
            return readCurrentCode(context); // Fallback
        }

        return smartContext.toString();
    }

    private void appendFileSnippet(ProjectContext context, StringBuilder buffer,
            java.util.Set<java.nio.file.Path> processedFiles, String fileName, int targetLine) {
        try {
            // Find the file in sandbox
            java.util.concurrent.atomic.AtomicReference<java.nio.file.Path> foundPath = new java.util.concurrent.atomic.AtomicReference<>();
            java.nio.file.Files.walk(context.getSandboxPath())
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .findFirst()
                    .ifPresent(foundPath::set);

            if (foundPath.get() == null)
                return;

            java.nio.file.Path path = foundPath.get();

            // Avoid duplicate processing (unless we want to splice multiple ranges, simpler
            // to skip for now or handle per file)
            // Refinement: If we already processed it, we might be missing a new range.
            // For MVP: If it's a TEST file, we likely read the whole thing once. If valid
            // source, maybe multiple snippets?
            // Let's just do: if Test -> Full Write (once). If Source -> Snippet.
            // Re-visiting same file for different lines is acceptable but complex to merge.
            // Simplified: Just write the snippet. Agents can handle repetition.

            String relativePath = context.getSandboxPath().relativize(path).toString();
            boolean isTestFile = fileName.endsWith("Test.java") || fileName.endsWith("Tests.java");

            if (processedFiles.contains(path) && isTestFile)
                return; // Already dumped full test

            java.util.List<String> lines = java.nio.file.Files.readAllLines(path);

            buffer.append("\n--- FILE: ").append(relativePath);

            if (isTestFile) {
                buffer.append(" (FULL CONTENT) ---\n");
                buffer.append(String.join("\n", lines));
                processedFiles.add(path);
            } else {
                buffer.append(" (LINES ").append(Math.max(1, targetLine - 10)).append("-")
                        .append(Math.min(lines.size(), targetLine + 10)).append(") ---\n");

                int start = Math.max(0, targetLine - 11); // 0-indexed, -10 lines
                int end = Math.min(lines.size(), targetLine + 10);

                for (int i = start; i < end; i++) {
                    buffer.append(String.format("%4d: %s\n", i + 1, lines.get(i)));
                }
                // Don't mark as processed so we can append other chunks if needed
                // But typically agents prefer one consolidated view.
                // Creating a "Map<Path, Set<Integers>>" is better but higher complexity.
                // Current approach: Appends snippets sequentially.
            }
            buffer.append("\n");

        } catch (Exception e) {
            // Ignore
        }
    }
}
