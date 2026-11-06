package io.github.llm4j.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.model.Media;
import io.github.llm4j.model.Message;

/**
 * The content parts of a user message that carries media, in the two OpenAI-style schemas. Pictures
 * and PDFs travel as data URIs, sound as base64 with its format; each part goes before the text it
 * is asked about, and an empty text is left out. Shapes confirmed against the real API on
 * 2026-11-06.
 */
final class MediaParts {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MediaParts() {}

    /** Chat completions: {@code image_url}, {@code file}, {@code input_audio} and {@code text}. */
    static ArrayNode chat(Message message) {
        ArrayNode parts = JSON.createArrayNode();
        for (Media item : message.getMedia()) {
            ObjectNode part = parts.addObject();
            switch (item.kind()) {
                case IMAGE -> part.put("type", "image_url")
                        .putObject("image_url")
                        .put("url", dataUri(item));
                case DOCUMENT -> {
                    ObjectNode file = part.put("type", "file").putObject("file");
                    file.put("filename", item.name()).put("file_data", dataUri(item));
                }
                case AUDIO -> part.put("type", "input_audio")
                        .putObject("input_audio")
                        .put("data", item.base64())
                        .put("format", audioFormat(item));
                default -> throw cannotSend(item, "chat completions");
            }
        }
        addText(parts, message, "text");
        return parts;
    }

    /** The Responses schema: {@code input_image}, {@code input_file} and {@code input_text}. */
    static ArrayNode responses(Message message) {
        ArrayNode parts = JSON.createArrayNode();
        for (Media item : message.getMedia()) {
            ObjectNode part = parts.addObject();
            switch (item.kind()) {
                case IMAGE -> part.put("type", "input_image").put("image_url", dataUri(item));
                case DOCUMENT -> part.put("type", "input_file")
                        .put("filename", item.name())
                        .put("file_data", dataUri(item));
                default -> throw cannotSend(item, "the Responses API");
            }
        }
        addText(parts, message, "input_text");
        return parts;
    }

    private static void addText(ArrayNode parts, Message message, String type) {
        if (!message.getContent().isEmpty() || message.getMedia().isEmpty()) {
            parts.addObject().put("type", type).put("text", message.getContent());
        }
    }

    private static String dataUri(Media item) {
        return "data:" + item.mediaType() + ";base64," + item.base64();
    }

    /** Chat completions takes {@code wav} and {@code mp3} sound and nothing else. */
    private static String audioFormat(Media item) {
        return switch (item.mediaType()) {
            case "audio/wav" -> "wav";
            case "audio/mpeg" -> "mp3";
            default -> throw new InvalidRequestException(
                    item.mediaType()
                            + " sound cannot be sent on chat completions, which takes wav and mp3;"
                            + " convert it first");
        };
    }

    private static InvalidRequestException cannotSend(Media item, String where) {
        return new InvalidRequestException(
                where
                        + " cannot take "
                        + item.kind().label()
                        + " here; remove it from the request");
    }
}
