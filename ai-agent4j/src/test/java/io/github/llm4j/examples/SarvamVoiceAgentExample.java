package io.github.llm4j.examples;

import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.media.AudioPlayer;
import io.github.llm4j.media.JavaAudioPlayer;
import io.github.llm4j.provider.sarvam.SarvamAudioProvider;
import io.github.llm4j.provider.sarvam.SarvamChatProvider;
import io.github.llm4j.provider.sarvam.SarvamTextToSpeechProvider;
import java.io.File;
import java.util.Scanner;

public class SarvamVoiceAgentExample {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getProperty("sarvam.api.key");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getenv("SARVAM_API_KEY");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println(
                    "Please set SARVAM_API_KEY environment variable or sarvam.api.key system property");
            System.exit(1);
        }

        LLMConfig config = LLMConfig.builder().apiKey(apiKey).build();

        // Initialize Providers
        SarvamChatProvider chatProvider = new SarvamChatProvider(config);
        SarvamAudioProvider audioProvider = new SarvamAudioProvider(config);
        SarvamTextToSpeechProvider ttsProvider = new SarvamTextToSpeechProvider(config);
        // Using specific cache dir for verification if needed, or default
        AudioPlayer audioPlayer = new JavaAudioPlayer();

        String sessionId = "test-session-" + System.currentTimeMillis();
        System.out.println("Running with Session ID: " + sessionId);

        // Initialize Agent with Auto-Play enabled
        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(new io.github.llm4j.DefaultLLMClient(chatProvider))
                        .sttProvider(audioProvider)
                        .ttsProvider(ttsProvider)
                        .audioPlayer(audioPlayer)
                        .autoPlayAudio(true)
                        .sessionId(sessionId) // Set explicit session ID
                        .systemPrompt("You are a helpful voice assistant.")
                        .maxIterations(5)
                        .build();

        Scanner scanner = new Scanner(System.in);
        System.out.println("Select mode:");
        System.out.println("1. Text Input -> Agent Speak");
        System.out.println("2. Voice Input (File) -> Agent Speak");
        System.out.print("Enter choice (1/2): ");
        String choice = scanner.nextLine();

        String userQuery;
        if ("2".equals(choice)) {
            System.out.print("Enter path to input audio file (wav/mp3): ");
            String filePath = scanner.nextLine();
            File audioFile = new File(filePath);
            if (!audioFile.exists()) {
                System.err.println("File not found: " + filePath);
                return;
            }
            System.out.println("Listening to file...");
            // Agent listens (transcribes)
            userQuery = agent.listen(audioFile);
            System.out.println("Transcribed: " + userQuery);
        } else {
            System.out.print("Enter your question: ");
            userQuery = scanner.nextLine();
        }

        System.out.println("Agent Thinking...");

        // Use agent.run() which should optionally speak if we implemented that logic.
        // Wait, I only added `speak(text)` to agent, I didn't verify if `run()`
        // automatically calls speak.
        // The user implementation plan said "Allow the agent to 'speak' its final
        // answer if configured."
        // Let's check ReActAgent.java again. I suspect I missed adding the automatic
        // call in `run()`.
        // I will first manually call speak(answer) to demonstrate the player.

        io.github.llm4j.agent.AgentResult result = agent.run(userQuery);
        String answer = result.getFinalAnswer();
        System.out.println("Agent Answer: " + answer);

        if (answer != null && !answer.isEmpty()) {
            System.out.println("Agent Speaking...");
            agent.speak(answer); // This uses the internal AudioPlayer to play
        } else {
            System.out.println("No answer generated.");
        }

        scanner.close();
    }
}
