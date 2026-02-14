package io.github.llm4j.kingini;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class KinginiApplication {

    public static void main(String[] args) {
        String apiKey = System.getProperty("sarvam.api.key");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getenv("SARVAM_API_KEY");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println(
                    "WARNING: SARVAM_API_KEY environment variable or sarvam.api.key system property not found. Voice features may not work.");
        } else {
            System.setProperty("sarvam.api.key", apiKey);
        }

        SpringApplication.run(KinginiApplication.class, args);
    }
}
