package io.github.llm4j.examples;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.TextToSpeechRequest;
import io.github.llm4j.model.TextToSpeechResponse;
import io.github.llm4j.provider.sarvam.SarvamTextToSpeechProvider;

import java.io.FileOutputStream;
import java.io.IOException;

public class SarvamTTSExample {

    public static void main(String[] args) {
        String apiKey = System.getenv("SARVAM_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Please set SARVAM_API_KEY environment variable");
            System.exit(1);
        }

        LLMConfig config = LLMConfig.builder()
                .apiKey(apiKey)
                .build();

        SarvamTextToSpeechProvider provider = new SarvamTextToSpeechProvider(config);

        TextToSpeechRequest request = TextToSpeechRequest.builder()
                .text("നമസ്കാരം! Sarvam AI-ലേക്ക് സ്വാഗതം.")
                .targetLanguageCode("ml-IN")
                .speaker("ritu")
                .pace(1.1)
                .speechSampleRate(22050)
                .enablePreprocessing(true)
                .model("bulbul:v3")
                .build();

        try {
            System.out.println("Generating speech...");
            TextToSpeechResponse response = provider.generateSpeech(request);

            String outputFileName = "output.wav";
            try (FileOutputStream fos = new FileOutputStream(outputFileName)) {
                fos.write(response.getAudioData());
            }

            System.out.println("Speech generated and saved to " + outputFileName);
        } catch (Exception e) {
            System.err.println("Error generating speech: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
