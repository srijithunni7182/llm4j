package io.github.llm4j.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility to map language names to Sarvam AI language codes.
 */
public class LanguageMapper {

    private static final Logger logger = LoggerFactory.getLogger(LanguageMapper.class);
    private static final Map<String, String> LANGUAGE_MAP = new HashMap<>();

    static {
        loadLanguages();
    }

    private static void loadLanguages() {
        try (InputStream is = LanguageMapper.class.getResourceAsStream("/sarvam_languages.json")) {
            if (is == null) {
                logger.warn("sarvam_languages.json not found in resources. Language mapping will be empty.");
                return;
            }
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> map = mapper.readValue(is, new TypeReference<Map<String, String>>() {
            });
            LANGUAGE_MAP.putAll(map);
        } catch (IOException e) {
            logger.error("Failed to load sarvam_languages.json", e);
        }
    }

    /**
     * Resolves a language name or code to a valid Sarvam language code.
     * Case-insensitive.
     *
     * @param input the language name (e.g., "Malayalam") or code (e.g., "ml-IN")
     * @return the corresponding language code, or the input if not found in the map
     *         (assuming it might already be a code)
     */
    public static String getLanguageCode(String input) {
        if (input == null || input.isEmpty()) {
            return "hi-IN"; // Default fallback
        }
        String normalized = input.toLowerCase().trim();
        return LANGUAGE_MAP.getOrDefault(normalized, input);
    }
}
