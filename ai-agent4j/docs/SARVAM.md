# Sarvam AI Integration

The `llm4j` library provides comprehensive support for [Sarvam AI](https://sarvam.ai/), enabling a wide range of capabilities for Indian languages.

## Features

- **Chat Completions**: Conversational AI models optimized for Indian languages (e.g., `sarvam-m`, `gemma-4b`).
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

```java
SarvamChatProvider chatProvider = new SarvamChatProvider(config);
LLMRequest request = LLMRequest.builder()
        .messages(List.of(Message.user("Namaste!")))
        .model("sarvam-m")
        .build();
LLMResponse response = chatProvider.chat(request);
System.out.println(response.getContent());
```

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
        .llmClient(new DefaultLLMClient(new SarvamChatProvider(config)))
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

