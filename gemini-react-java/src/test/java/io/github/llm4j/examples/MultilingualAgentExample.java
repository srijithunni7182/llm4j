package io.github.llm4j.examples;

import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.sarvam.SarvamAudioProvider;
import io.github.llm4j.provider.sarvam.SarvamChatProvider;
import io.github.llm4j.provider.sarvam.SarvamTextToSpeechProvider;

/**
 * Example demonstrating the ReAct Agent's ability to speak in different
 * languages.
 */
public class MultilingualAgentExample {

    public static void main(String[] args) {
        String apiKey = System.getenv("SARVAM_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getProperty("sarvam.api.key");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Please set SARVAM_API_KEY environment variable or sarvam.api.key system property");
            System.exit(1);
        }

        LLMConfig config = LLMConfig.builder()
                .apiKey(apiKey)
                .build();

        // 1. Malayalam Agent
        System.out.println("=== Creating Malayalam Agent ===");
        ReActAgent malayalamAgent = ReActAgent.builder()
                .llmClient(new io.github.llm4j.DefaultLLMClient(new SarvamChatProvider(config)))
                .ttsProvider(new SarvamTextToSpeechProvider(config))
                .sttProvider(new SarvamAudioProvider(config)) // Optional but good practice
                .ttsLanguage("Malayalam") // Using name
                .build();

        try {
            System.out.println("Speaking in Malayalam...");
            malayalamAgent.speak("Namaskaram! Sukhamaano?");
            Thread.sleep(3000); // Wait for audio
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 2. Tamil Agent
        System.out.println("\n=== Creating Tamil Agent ===");
        ReActAgent tamilAgent = ReActAgent.builder()
                .llmClient(new io.github.llm4j.DefaultLLMClient(new SarvamChatProvider(config)))
                .ttsProvider(new SarvamTextToSpeechProvider(config))
                .ttsLanguage("Tamil") // Using name
                .build();

        try {
            System.out.println("Speaking in Tamil...");
            tamilAgent.speak("Vanakkam! Eppadi irukkinga?");
            Thread.sleep(3000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 3. Hindi Agent
        System.out.println("\n=== Creating Hindi Agent ===");
        ReActAgent hindiAgent = ReActAgent.builder()
                .llmClient(new io.github.llm4j.DefaultLLMClient(new SarvamChatProvider(config)))
                .ttsProvider(new SarvamTextToSpeechProvider(config))
                .ttsLanguage("Hindi") // Using name
                .build();

        try {
            System.out.println("Speaking in Hindi...");
            hindiAgent.speak("Namaste! Aap kaise hain?");
            Thread.sleep(3000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.exit(0);
    }
}
