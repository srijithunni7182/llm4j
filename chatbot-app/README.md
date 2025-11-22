# Simple Chatbot Application

A command-line chatbot application built using the [LLM4J](../README.md) library.

## Features

- 💬 **Interactive CLI**: Beautiful terminal interface with colors and ANSI support
- 🤖 **Google Gemini**: Uses the tested and verified LLM4J library
- 🔄 **Auto-Discovery**: Automatically detects latest available Gemini models
- 📝 **Conversation History**: Maintains context across messages
- 🎯 **Interactive Commands**: Built-in commands for managing conversations
- 🎨 **Colored Output**: Enhanced readability with ANSI colors
- ⚙️ **Configurable**: Custom models and system prompts

## Prerequisites

- Java 17 or higher
- Maven 3.6+
- Google API key ([Get one here](https://makersuite.google.com/app/apikey))

Set your API key:
```bash
export GOOGLE_API_KEY="your-api-key-here"
```

## Quick Start

### 1. Set API Key

```bash
export OPENAI_API_KEY="your-api-key-here"
```

### 2. Run the Chatbot

**Default (auto-detect provider and use default model):**
```bash
mvn exec:java
```

**With specific model:**
```bash
# Use gemini-1.5-pro instead of gemini-1.5-flash
mvn exec:java -Dexec.args="-m gemini-1.5-pro"

# Use GPT-4
mvn exec:java -Dexec.args="-m gpt-4"
```

**With custom system prompt:**
```bash
mvn exec:java -Dexec.args="-p 'You are a coding expert'"
```

Or build and run the JAR:

```bash
mvn clean package
java -jar target/chatbot-app-1.0.0-SNAPSHOT-jar-with-dependencies.jar -m gemini-1.5-flash
```

### Command-Line Options

```bash
  -m, --model <name>    Specify the model to use
                        OpenAI: gpt-3.5-turbo, gpt-4, gpt-4-turbo
                        Anthropic: claude-3-haiku-20240307, claude-3-sonnet-20240229
                        Google: gemini-1.5-flash, gemini-1.5-pro
  -p, --prompt <text>   Custom system prompt
  -h, --help            Show help message
```

## Usage

Once started, you'll see a welcome screen:

```
╔════════════════════════════════════════╗
║       Welcome to LLM4J Chatbot!       ║
╚════════════════════════════════════════╝

Type your messages and press Enter to chat.
```

### Commands

- `/help` - Show help message
- `/history` - View conversation history
- `/clear` - Clear conversation history
- `/quit` or `/exit` - Exit the chatbot

### Example Conversation

```
You: Hello! Who are you?
Assistant: I'm an AI assistant powered by LLM4J. How can I help you today?
  [Tokens: 23]

You: What's 15 * 23?
Assistant: 15 * 23 = 345
  [Tokens: 15]

You: Thanks!
Assistant: You're welcome! Let me know if you need anything else.
  [Tokens: 18]
```

## Features in Detail

### Conversation History

The chatbot maintains the last 20 messages by default to provide context:

```java
ConversationHistory history = new ConversationHistory(20);
```

### Multi-Turn Conversations

The chatbot remembers previous messages:

```
You: My name is John
Assistant: Nice to meet you, John! How can I assist you today?

You: What's my name?
Assistant: Your name is John.
```

### Automatic Provider Selection

The chatbot automatically chooses the first available provider:
1. OpenAI (GPT-3.5-Turbo)
2. Anthropic (Claude Haiku)
3. Google (Gemini Pro)

### Error Handling

Gracefully handles:
- Authentication errors
- Rate limiting
- Invalid requests
- Network issues

## Configuration

Edit `ChatbotApp.java` to customize:

```java
// Change system prompt
private static final String DEFAULT_SYSTEM_PROMPT = 
    "You are a helpful, friendly AI assistant.";

// Change temperature
.temperature(0.7)  // 0.0 = deterministic, 1.0 = creative

// Change max tokens
.maxTokens(500)    // Maximum response length

// Change history size
new ConversationHistory(20)  // Number of messages to keep
```

## Building

### Development

```bash
# Compile and run
mvn clean compile exec:java

# Run tests (if added)
mvn test
```

### Production

```bash
# Build executable JAR
mvn clean package

# Run the JAR
java -jar target/chatbot-app-1.0.0-SNAPSHOT-jar-with-dependencies.jar
```

## Project Structure

```
chatbot-app/
├── pom.xml                                      # Maven configuration
├── README.md                                    # This file
└── src/
    └── main/
        └── java/
            └── io/github/chatbot/
                ├── ChatbotApp.java              # Main application
                └── ConversationHistory.java     # History management
```

## Dependencies

- **LLM4J** (0.1.0-SNAPSHOT) - LLM client library
- **JLine** (3.25.0) - Terminal handling
- **Jansi** (2.4.1) - ANSI colors

## Troubleshooting

### "No API key found"

Make sure you've set one of the environment variables:
```bash
# Set your Google API key
export GOOGLE_API_KEY="your-api-key-here"

# Run the chatbot
cd chatbot-app
mvn exec:java
```

### "Authentication failed"

Check that your API key is valid and has not expired.

### Rate limiting

If you see rate limit errors, wait a few seconds before continuing.

## Extending

### Custom System Prompt

```java
String customPrompt = "You are a coding expert. Provide code examples.";
ChatbotApp chatbot = new ChatbotApp(client, customPrompt);
```

### Different Models

```java
LLMConfig config = LLMConfig.builder()
        .apiKey(apiKey)
        .defaultModel("gpt-4")  // Use GPT-4 instead of 3.5
        .build();
```

### Add Commands

Modify the command handling section in `ChatbotApp.java`:

```java
if (trimmed.equals("/mycommand")) {
    // Handle your custom command
    continue;
}
```

## License

Same as LLM4J - MIT License
