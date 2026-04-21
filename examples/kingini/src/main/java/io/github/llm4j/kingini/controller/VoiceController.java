package io.github.llm4j.kingini.controller;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.media.AudioPlayer;
import io.github.llm4j.model.TextToSpeechRequest;
import io.github.llm4j.model.TextToSpeechResponse;
import io.github.llm4j.provider.sarvam.SarvamAudioProvider;
import io.github.llm4j.provider.sarvam.SarvamChatProvider;
import io.github.llm4j.provider.sarvam.SarvamTextToSpeechProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.annotation.PostConstruct;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*") // Allow requests from frontend
public class VoiceController {

    private static final Logger logger = Logger.getLogger(VoiceController.class.getName());

    private ReActAgent agent;
    private SarvamAudioProvider audioProvider;
    private SarvamTextToSpeechProvider ttsProvider;
    private SarvamChatProvider chatProvider;

    @PostConstruct
    public void init() {
        String apiKey = System.getProperty("sarvam.api.key");
        if (apiKey == null)
            apiKey = System.getenv("SARVAM_API_KEY");

        if (apiKey == null) {
            logger.severe("SARVAM_API_KEY not found!");
            return;
        }

        LLMConfig config = LLMConfig.builder().apiKey(apiKey).build();

        this.chatProvider = new SarvamChatProvider(config);
        this.audioProvider = new SarvamAudioProvider(config);
        this.ttsProvider = new SarvamTextToSpeechProvider(config);

        // We don't need the audio player here as the browser will play it
        // AudioPlayer interface: void play(byte[]); void play(File);
        AudioPlayer noOpPlayer = new AudioPlayer() {
            @Override
            public void play(File audioFile) {
            }

            @Override
            public void play(byte[] audioData) {
            }
        };

        this.agent = ReActAgent.builder()
                .llmClient(new io.github.llm4j.DefaultLLMClient(chatProvider))
                .sttProvider(audioProvider)
                .ttsProvider(ttsProvider)
                .audioPlayer(noOpPlayer)
                .autoPlayAudio(false) // Backend shouldn't play audio
                .systemPrompt(
                        "You are Kingini, a cute and friendly female cat living in a traditional Kerala ancestral home. You speak only in Malayalam. You love answering questions from children. As a cat, you must frequently use the sound 'Meow' (written as 'മ്യാവൂ' in Malayalam) naturally in your starting or ending of sentences. Keep answers short and playful. Use Malayalam script for text responses.")
                .maxIterations(5)
                .build();
    }

    @PostMapping("/audio")
    public ResponseEntity<Map<String, String>> handleAudio(@RequestParam("audio") MultipartFile audioFile) {
        try {
            // Save temp file
            Path tempFile = Files.createTempFile("kingini-input", ".wav");
            audioFile.transferTo(tempFile.toFile());

            logger.info("Received audio file: " + tempFile.toString());

            // 1. STT: Transcribe Audio
            String userQuery = agent.listen(tempFile.toFile());
            logger.info("Transcribed: " + userQuery);

            if (userQuery == null || userQuery.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Could not understand audio"));
            }

            // 2. LLM: Process Query
            AgentResult result = agent.run(userQuery);
            String answer = result.getFinalAnswer();
            logger.info("Agent Answer: " + answer);

            // 3. TTS: Generate Audio for Answer
            // Since agent.run() includes tts calls if configured, we might want to do it
            // explicitly here
            // to get the audio data to send back to client.
            // ReActAgent logic currently plays audio if autoPlay is true.
            // We need the audio bytes.
            // Let's call TTS provider directly for the final answer to get bytes.

            byte[] audioBytes = null;
            if (answer != null && !answer.isEmpty()) {
                TextToSpeechRequest ttsRequest = TextToSpeechRequest.builder()
                        .text(answer)
                        .targetLanguageCode("ml-IN") // Malayalam
                        .speaker("ritu") // Female voice (User suggested)
                        .build();

                TextToSpeechResponse ttsResponse = ttsProvider.generateSpeech(ttsRequest);
                if (ttsResponse != null && ttsResponse.getAudioData() != null) {
                    audioBytes = ttsResponse.getAudioData();
                }
            }

            Map<String, String> response = new HashMap<>();
            response.put("user_query", userQuery);
            response.put("agent_response", answer);
            if (audioBytes != null) {
                response.put("audio_base64", Base64.getEncoder().encodeToString(audioBytes));
            }

            // Cleanup
            Files.deleteIfExists(tempFile);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
