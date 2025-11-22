package io.github.chatbot;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.*;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.google.GoogleProvider;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiConsole;
import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.fusesource.jansi.Ansi.Color.*;
import static org.fusesource.jansi.Ansi.ansi;

/**
 * Simple chatbot application using LLM4J library.
 */
public class ChatbotApp {

    private static final String DEFAULT_SYSTEM_PROMPT = "You are a helpful, friendly AI assistant. " +
            "Provide clear, concise, and accurate responses.";

    private final LLMClient llmClient;
    private ConversationHistory history;
    private String systemPrompt;
    private final Terminal terminal;
    private final LineReader reader;

    public ChatbotApp(LLMClient llmClient, String systemPrompt) throws IOException {
        this.llmClient = llmClient;
        this.history = new ConversationHistory();
        this.systemPrompt = systemPrompt != null ? systemPrompt : DEFAULT_SYSTEM_PROMPT;

        // Add system prompt
        if (this.systemPrompt != null && !this.systemPrompt.isEmpty()) {
            history.addSystemMessage(this.systemPrompt);
        }

        // Setup terminal
        this.terminal = TerminalBuilder.builder()
                .system(true)
                .build();

        this.reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
    }

    public void run() {
        AnsiConsole.systemInstall();

        printWelcome();

        while (true) {
            try {
                // Read user input
                String prompt = reader.readLine(
                        ansi().fg(CYAN).a("You: ").reset().toString());

                // Handle special commands
                if (prompt == null || prompt.trim().isEmpty()) {
                    continue;
                }

                String trimmed = prompt.trim().toLowerCase();
                if (trimmed.equals("/quit") || trimmed.equals("/exit")) {
                    printGoodbye();
                    break;
                } else if (trimmed.equals("/clear")) {
                    history.clear();
                    System.out.println(ansi().fg(YELLOW).a("✓ Conversation history cleared").reset());
                    continue;
                } else if (trimmed.equals("/help")) {
                    printHelp();
                    continue;
                } else if (trimmed.equals("/history")) {
                    printHistory();
                    continue;
                } else if (trimmed.startsWith("/save ")) {
                    handleSaveCommand(prompt.trim().substring(6));
                    continue;
                } else if (trimmed.startsWith("/load ")) {
                    handleLoadCommand(prompt.trim().substring(6));
                    continue;
                } else if (trimmed.equals("/list")) {
                    handleListCommand();
                    continue;
                } else if (trimmed.startsWith("/delete ")) {
                    handleDeleteCommand(prompt.trim().substring(8));
                    continue;
                }

                // Add user message to history
                history.addUserMessage(prompt);

                // Build request with full history
                LLMRequest request = LLMRequest.builder()
                        .messages(history.getMessages())
                        .temperature(0.7)
                        .maxTokens(500)
                        .build();

                // Get response
                System.out.print(ansi().fg(GREEN).a("Assistant: ").reset());

                try {
                    LLMResponse response = llmClient.chat(request);
                    String answer = response.getContent();

                    // Print response
                    System.out.println(answer);

                    // Add to history
                    history.addAssistantMessage(answer);

                    // Print token usage
                    if (response.getTokenUsage() != null) {
                        System.out.println(ansi().fgBright(BLACK).a(
                                String.format("  [Tokens: %d]",
                                        response.getTokenUsage().getTotalTokens()))
                                .reset());
                    }

                    System.out.println(); // Empty line

                } catch (AuthenticationException e) {
                    printError("Authentication failed. Please check your API key.");
                    break;
                } catch (RateLimitException e) {
                    printError("Rate limit exceeded. Please wait and try again.");
                } catch (InvalidRequestException e) {
                    printError("Invalid request: " + e.getMessage());
                } catch (LLMException e) {
                    printError("Error: " + e.getMessage());
                }

            } catch (UserInterruptException e) {
                // Ctrl+C pressed
                printGoodbye();
                break;
            } catch (EndOfFileException e) {
                // EOF (Ctrl+D)
                break;
            }
        }

        AnsiConsole.systemUninstall();
    }

    private void printWelcome() {
        System.out.println(ansi().bold().fg(MAGENTA).a(
                "\n" +
                        "╔════════════════════════════════════════╗\n" +
                        "║       Welcome to LLM4J Chatbot!       ║\n" +
                        "╚════════════════════════════════════════╝\n")
                .reset());

        System.out.println(ansi().fg(YELLOW).a(
                "Type your messages and press Enter to chat.\n").reset());

        printHelp();
    }

    private void printHelp() {
        System.out.println(ansi().fg(CYAN).a("Commands:").reset());
        System.out.println("  /help          - Show this help message");
        System.out.println("  /history       - Show conversation history");
        System.out.println("  /clear         - Clear conversation history");
        System.out.println("  /save <name>   - Save current conversation");
        System.out.println("  /load <name>   - Load saved conversation");
        System.out.println("  /list          - List all saved conversations");
        System.out.println("  /delete <name> - Delete a saved conversation");
        System.out.println("  /quit          - Exit the chatbot");
        System.out.println();
    }

    private void printHistory() {
        System.out.println(ansi().fg(CYAN).a("\n=== Conversation History ===").reset());
        for (int i = 0; i < history.getMessages().size(); i++) {
            var msg = history.getMessages().get(i);
            Ansi.Color color = switch (msg.getRole()) {
                case SYSTEM -> YELLOW;
                case USER -> CYAN;
                case ASSISTANT -> GREEN;
            };
            System.out.println(ansi().fg(color).a(
                    String.format("[%d] %s: %s",
                            i + 1,
                            msg.getRole().getValue(),
                            msg.getContent().substring(0, Math.min(100, msg.getContent().length())) +
                                    (msg.getContent().length() > 100 ? "..." : "")))
                    .reset());
        }
        System.out.println();
    }

    private void printError(String error) {
        System.out.println(ansi().fg(RED).bold().a("✗ " + error).reset());
        System.out.println();
        System.out.println();
    }

    private void handleSaveCommand(String name) {
        if (name == null || name.trim().isEmpty()) {
            printError("Usage: /save <name>");
            return;
        }

        try {
            ConversationPersistence.saveConversation(name, history, systemPrompt);
            System.out.println(ansi().fg(GREEN).a("✓ Conversation saved as '" + name + "'").reset());
            System.out.println();
        } catch (IOException e) {
            printError("Failed to save conversation: " + e.getMessage());
        }
    }

    private void handleLoadCommand(String name) {
        if (name == null || name.trim().isEmpty()) {
            printError("Usage: /load <name>");
            return;
        }

        try {
            ConversationPersistence.LoadedConversation loaded = ConversationPersistence.loadConversation(name);
            this.history = loaded.getHistory();
            this.systemPrompt = loaded.getSystemPrompt();
            System.out.println(ansi().fg(GREEN).a("✓ Loaded conversation '" + name + "' with " +
                    history.size() + " messages").reset());
            System.out.println();
        } catch (IOException e) {
            printError("Failed to load conversation: " + e.getMessage());
        }
    }

    private void handleListCommand() {
        try {
            var conversations = ConversationPersistence.listConversations();

            if (conversations.isEmpty()) {
                System.out.println(ansi().fg(YELLOW).a("No saved conversations found.").reset());
                System.out.println();
                return;
            }

            System.out.println(ansi().fg(CYAN).a("\n=== Saved Conversations ===").reset());
            for (var conv : conversations) {
                System.out.println(ansi().fg(GREEN).a(
                        String.format("  • %s (%d messages, saved: %s)",
                                conv.getName(),
                                conv.getMessageCount(),
                                conv.getFormattedDate()))
                        .reset());
            }
            System.out.println();
        } catch (IOException e) {
            printError("Failed to list conversations: " + e.getMessage());
        }
    }

    private void handleDeleteCommand(String name) {
        if (name == null || name.trim().isEmpty()) {
            printError("Usage: /delete <name>");
            return;
        }

        try {
            boolean deleted = ConversationPersistence.deleteConversation(name);
            if (deleted) {
                System.out.println(ansi().fg(GREEN).a("✓ Deleted conversation '" + name + "'").reset());
            } else {
                System.out.println(ansi().fg(YELLOW).a("Conversation '" + name + "' not found").reset());
            }
            System.out.println();
        } catch (IOException e) {
            printError("Failed to delete conversation: " + e.getMessage());
        }
    }

    private void printGoodbye() {
        System.out.println(ansi().fg(MAGENTA).a(
                "\n" +
                        "Thank you for using LLM4J Chatbot!\n" +
                        "Goodbye! 👋\n")
                .reset());
    }

    public static void main(String[] args) {
        try {
            // Parse command-line arguments
            String model = null;
            String systemPrompt = DEFAULT_SYSTEM_PROMPT;

            for (int i = 0; i < args.length; i++) {
                if ((args[i].equals("-m") || args[i].equals("--model")) && i + 1 < args.length) {
                    model = args[++i];
                } else if ((args[i].equals("-p") || args[i].equals("--prompt")) && i + 1 < args.length) {
                    systemPrompt = args[++i];
                } else if (args[i].equals("-h") || args[i].equals("--help")) {
                    printUsage();
                    System.exit(0);
                }
            }

            // Determine which LLM provider to use based on available API keys
            LLMClient client = createLLMClient(model);

            // Create and run chatbot
            ChatbotApp chatbot = new ChatbotApp(client, systemPrompt);
            chatbot.run();

        } catch (Exception e) {
            System.err.println("Failed to start chatbot: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar chatbot-app.jar [OPTIONS]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -m, --model <name>    Specify the model to use");
        System.out.println("                        Available: gemini-1.5-flash, gemini-1.5-pro, gemini-2.0-flash");
        System.out.println("  -p, --prompt <text>   Custom system prompt");
        System.out.println("  -h, --help            Show this help message");
        System.out.println();
        System.out.println("Environment Variables:");
        System.out.println("  GOOGLE_API_KEY        Google API key (required)");
    }

    private static LLMClient createLLMClient(String customModel) {
        // Check for Google API key
        String googleKey = System.getenv("GOOGLE_API_KEY");
        if (googleKey == null || googleKey.isEmpty()) {
            System.err.println("No Google API key found!");
            System.err.println();
            System.err.println("Please set the GOOGLE_API_KEY environment variable:");
            System.err.println("  export GOOGLE_API_KEY='your-api-key-here'");
            System.err.println();
            System.err.println("Get your API key from: https://makersuite.google.com/app/apikey");
            System.exit(1);
        }

        String model = customModel;

        // If no custom model specified, try to discover available models
        if (model == null) {
            try {
                // Create a temporary provider to discover models
                LLMConfig tempConfig = LLMConfig.builder()
                        .apiKey(googleKey)
                        .build();
                GoogleProvider tempProvider = new GoogleProvider(tempConfig);

                String discoveredModel = tempProvider.getFirstAvailableModel();
                if (discoveredModel != null) {
                    model = discoveredModel;
                    System.out.println(ansi().fg(YELLOW).a("  Auto-discovered model: " + model).reset());
                } else {
                    // Fallback to a common model name
                    model = "gemini-1.5-flash";
                    System.out.println(
                            ansi().fg(YELLOW).a("  Could not discover models, using default: " + model).reset());
                }
            } catch (Exception e) {
                model = "gemini-1.5-flash";
                System.out.println(ansi().fg(YELLOW).a("  Model discovery failed, using default: " + model).reset());
            }
        }

        System.out.println(ansi().fg(GREEN).a("✓ Using Google Gemini: " + model).reset());
        LLMConfig config = LLMConfig.builder()
                .apiKey(googleKey)
                .defaultModel(model)
                .build();
        return new DefaultLLMClient(new GoogleProvider(config));
    }
}
