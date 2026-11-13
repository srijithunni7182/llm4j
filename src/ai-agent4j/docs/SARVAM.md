# Sarvam AI Integration

The `llm4j` library provides comprehensive support for [Sarvam AI](https://sarvam.ai/), enabling a wide range of capabilities for Indian languages.

## Features

- **Chat Completions (V2)**: Sarvam's own `sarvam-105b` and the open-weight models of its [Token Factory](https://docs.sarvam.ai/api/token-factory/models) (`glm5.3`, `glm5.3-flash`, `gemma4`, `deepseekv4.1-flash-1017`, and provisioned models such as `kimi-k3.1-pro` and `qwen38-27b`) on the same key, with native tools, streaming reasoning, structured output and pictures.
- **Speech-to-Text (STT)**: High-accuracy ASR (Automatic Speech Recognition) for Indian languages.
- **Text-to-Speech (TTS)**: Natural sounding speech synthesis.
- **Translation**: Translate between English and various Indian languages.
- **Transliterate**: Convert text between scripts (e.g., Hindi to Latin).
- **Language Detection**: Identify the language of a given text.
- **Voice Agents**: Build agents that can speak and listen using Sarvam's voice stack.

## Configuration

To use Sarvam AI providers, you need a Sarvam API key.

1.  **Get an API Key**: Sign up at [Sarvam Dashboard](https://dashboard.sarvam.ai/).
2.  **Configure**:
    ```java
    LLMConfig config = LLMConfig.builder()
            .apiKey(System.getenv("SARVAM_API_KEY"))
            .build();
    ```

## Usage

### Chat

Chat goes through `OpenAiCompatibleProvider.sarvam`, which calls Sarvam's V2 chat completions (`https://api.sarvam.ai/v2/chat/completions`):

```java
LLMClient client = new DefaultLLMClient(OpenAiCompatibleProvider.sarvam(config));
LLMResponse response = client.chat(LLMRequest.builder()
        .addUserMessage("Namaste!")
        .model("sarvam-105b")                 // or "glm5.3", "gemma4", "deepseekv4.1-flash-1017", …
        .build());
System.out.println(response.getContent());
```

- **Thinking.** `sarvam-105b`, the DeepSeek and the Qwen models think by default; the provider sends `reasoning_effort: "none"` unless a streamed call asks to see reasoning (`LLMRequest.Builder.reasoning(true)`). `gemma4` thinks only when asked. GLM and Kimi always think. For another effort, `addParameter("reasoning_effort", "high")`.
- **Tools, schemas and pictures.** Every chat model takes native tools and a JSON-Schema `responseFormat`. `gemma4`, `glm5.3-flash`, the DeepSeek flash models, `kimi-k3.1-pro` and `qwen38-27b` take pictures.
- **In Loom**, `model: "sarvam/glm5.3"` (or `provider Box { use: sarvam }`); **in Spring**, `provider: sarvam`. Both read `SARVAM_API_KEY` and, optionally, `SARVAM_BASE_URL` (the host, without `/v2`).
- **Provisioned models** (`deepseekv4-pro`, `kimi-k3.1-pro`, `qwen38-27b`, …) answer 404 until Sarvam grants your organisation access. To see what your key can use, call `OpenAiCompatibleProvider.sarvam(config).listModels()` (`GET /v2/models`).
- **`sarvam-m` is no longer served.** `SarvamChatProvider`, the V1 text-only provider, is deprecated and now defaults to `sarvam-105b`; move to `OpenAiCompatibleProvider.sarvam`. See [OpenAI-family providers](../wiki/OpenAI-Family-Providers.md#sarvam-ai-and-its-token-factory) for what the dialect sends.

### Speech-to-Text (STT)

```java
SarvamAudioProvider audioProvider = new SarvamAudioProvider(config);
TranscriptionRequest request = TranscriptionRequest.builder()
        .languageCode("hi-IN") // Optional
        .build();
TranscriptionResponse response = audioProvider.transcribe(new File("input.wav"), request);
System.out.println(response.getText());
```

### Text-to-Speech (TTS)

```java
SarvamTextToSpeechProvider ttsProvider = new SarvamTextToSpeechProvider(config);
TextToSpeechRequest request = TextToSpeechRequest.builder()
        .text("नमस्ते दुनिया")
        .targetLanguageCode("hi-IN")
        .build();
TextToSpeechResponse response = ttsProvider.generateSpeech(request);
Files.write(Paths.get("output.wav"), response.getAudioData());
```

### Translation

```java
SarvamTextProvider textProvider = new SarvamTextProvider(config);
TranslationRequest request = TranslationRequest.builder()
        .text("Hello World")
        .sourceLanguageCode("en-IN")
        .targetLanguageCode("hi-IN")
        .build();
TranslationResponse response = textProvider.translate(request);
System.out.println(response.getTranslatedText());
```

## Voice Agents

You can create agents that "speak" and "listen" using Sarvam's TTS and STT capabilities.

```java
ReActAgent agent = ReActAgent.builder()
        .llmClient(new DefaultLLMClient(OpenAiCompatibleProvider.sarvam(config)))
        .ttsProvider(new SarvamTextToSpeechProvider(config))
        .sttProvider(new SarvamAudioProvider(config))
        .audioPlayer(new JavaAudioPlayer()) // Enable audio playback
        .autoPlayAudio(true)                // Automatically speak responses
        .ttsLanguage("Malayalam")           // Optional: Set default TTS language
        .sessionId("user-session-123")      // Optional: Session ID for caching
        .build();

// Simulate listening from an audio file
String userRequest = agent.listen(new File("user_query.wav"));
AgentResult result = agent.run(userRequest);

// Agent speaks result automatically if autoPlayAudio is true
```

## Multi-Language Support

The `ReActAgent` supports multi-language text-to-speech. You can configure the agent to speak in a specific language using the `.ttsLanguage()` builder method.

**Supported Languages:**
- Hindi, Bengali, Tamil, Telugu, Marathi, Gujarati, Kannada, Malayalam, Punjabi, Odia

**Model Selection:**
- Default: `bulbul:v1` (Smart default for Indian languages)
- Override: Use `.ttsModel("bulbul:v2")` to specify a model.

## Audio Caching

The `JavaAudioPlayer` automatically caches generated audio files to avoid redundant API calls and improve performance.

- **Session-Scoped**: Audio files are stored in `{java.io.tmpdir}/llm4j-audio-cache/{sessionId}/`.
- **Deduplication**: Files are named using a content-based hash, so identical text/audio is reused.

