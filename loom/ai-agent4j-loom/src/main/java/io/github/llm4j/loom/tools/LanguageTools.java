package io.github.llm4j.loom.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.model.TextToSpeechRequest;
import io.github.llm4j.model.TranscriptionRequest;
import io.github.llm4j.model.TranslationRequest;
import io.github.llm4j.model.TransliterationRequest;
import io.github.llm4j.provider.sarvam.SarvamAudioProvider;
import io.github.llm4j.provider.sarvam.SarvamTextProvider;
import io.github.llm4j.provider.sarvam.SarvamTextToSpeechProvider;
import io.github.llm4j.util.LanguageMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Voice and Indic language tools, served by Sarvam: {@code translate}, {@code transliterate},
 * {@code detect_language}, {@code speak} and {@code transcribe}. Usable by name (the key comes from
 * {@code SARVAM_API_KEY}) or declared with options ({@code tool Hindi { use: translate target: "hi-IN" }}).
 */
public final class LanguageTools {

    private LanguageTools() {}

    /** Kinds whose key (and endpoint override) default to SARVAM_API_KEY / SARVAM_BASE_URL. */
    public static final Set<String> KINDS = Set.of("translate", "transliterate", "detect_language", "speak", "transcribe");

    static final Map<String, ToolDef.OptionValue> DEFAULTS = Map.of(
            "api_key", ToolDef.OptionValue.env("SARVAM_API_KEY"),
            "base_url", ToolDef.OptionValue.env("SARVAM_BASE_URL"));

    /** Sarvam's configuration from a tool's (or agent voice's) resolved options. */
    public static LLMConfig sarvam(String apiKey, String baseUrl) {
        LLMConfig.Builder b = LLMConfig.builder().apiKey(apiKey);
        if (baseUrl != null && !baseUrl.isBlank()) b.baseUrl(baseUrl);
        return b.build();
    }

    static void registerAll(ToolFactory factory) {
        factory.register(kind("translate", Set.of("target", "source"), (name, o, dir) -> {
            SarvamTextProvider sarvam = new SarvamTextProvider(sarvam(o.get("api_key"), o.get("base_url")));
            return tool(name, "Translates text between languages (Indian languages and English). Arguments: "
                    + "text (string), target (optional language, e.g. \"hi-IN\" or \"Hindi\""
                    + (o.containsKey("target") ? "; default " + o.get("target") : "") + "), source (optional).", args -> {
                String text = text(args, "text");
                String target = arg(args, "target", o.get("target"));
                if (target == null) return "Error: give target, the language to translate into (e.g. hi-IN).";
                TranslationRequest.Builder r = TranslationRequest.builder().text(text).targetLanguageCode(code(target));
                String source = arg(args, "source", o.get("source"));
                if (source != null) r.sourceLanguageCode(code(source));
                return sarvam.translate(r.build()).getTranslatedText();
            });
        }));
        factory.register(kind("transliterate", Set.of("target", "source"), (name, o, dir) -> {
            SarvamTextProvider sarvam = new SarvamTextProvider(sarvam(o.get("api_key"), o.get("base_url")));
            return tool(name, "Writes text in another script without translating it (e.g. Hindi in Latin letters). "
                    + "Arguments: text (string), target (optional language code"
                    + (o.containsKey("target") ? "; default " + o.get("target") : "") + "), source (optional).", args -> {
                String text = text(args, "text");
                String target = arg(args, "target", o.get("target"));
                if (target == null) return "Error: give target, the language code whose script to use (e.g. hi-IN).";
                TransliterationRequest.Builder r = TransliterationRequest.builder().text(text).targetLanguageCode(code(target));
                String source = arg(args, "source", o.get("source"));
                if (source != null) r.sourceLanguageCode(code(source));
                return sarvam.transliterate(r.build()).getTransliteratedText();
            });
        }));
        factory.register(kind("detect_language", Set.of(), (name, o, dir) -> {
            SarvamTextProvider sarvam = new SarvamTextProvider(sarvam(o.get("api_key"), o.get("base_url")));
            return tool(name, "Identifies the language of a text. Arguments: text (string). Returns a language code such as hi-IN.",
                    args -> sarvam.detectLanguage(text(args, "text")).getDetectedLanguageCode());
        }));
        factory.register(new SpeakKind());
        factory.register(kind("transcribe", Set.of("language", "model", "translate_to_english"), (name, o, dir) -> {
            SarvamAudioProvider sarvam = new SarvamAudioProvider(sarvam(o.get("api_key"), o.get("base_url")));
            return tool(name, "Turns speech in an audio file into text. Arguments: path (string, an audio file in the "
                    + "script's directory).", args -> {
                Path file = SafePaths.inside(dir, text(args, "path"));
                if (!Files.isRegularFile(file)) return "Error: no audio file at " + args.get("path");
                return transcribe(sarvam, file, o.get("language"), o.get("model"), Boolean.parseBoolean(o.get("translate_to_english")));
            });
        }));
    }

    /** Speech to text through Sarvam. */
    public static String transcribe(SarvamAudioProvider sarvam, Path file, String language, String model, boolean toEnglish) {
        TranscriptionRequest.Builder r = TranscriptionRequest.builder();
        if (language != null) r.languageCode(code(language));
        if (model != null) r.model(model);
        if (toEnglish) r.translateToEnglish(true);
        return sarvam.transcribe(file.toFile(), r.build()).getText();
    }

    /** Text to speech through Sarvam; the WAV bytes. */
    public static byte[] synthesize(SarvamTextToSpeechProvider sarvam, String text, String language, String voice, String model) {
        TextToSpeechRequest.Builder r = TextToSpeechRequest.builder().text(text)
                .targetLanguageCode(code(language != null ? language : "hi-IN"))
                .model(model != null ? model : "bulbul:v2");
        if (voice != null) r.speaker(voice);
        return sarvam.generateSpeech(r.build()).getAudioData();
    }

    /** {@code speak}: writes numbered WAV files under {@code out} (inside the script's directory). */
    private static final class SpeakKind implements ToolKind {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public String name() {
            return "speak";
        }

        @Override
        public Set<String> required() {
            return Set.of("api_key");
        }

        @Override
        public Set<String> optional() {
            return Set.of("base_url", "language", "voice", "model", "out");
        }

        @Override
        public Set<String> secrets() {
            return Set.of("api_key");
        }

        @Override
        public Map<String, ToolDef.OptionValue> defaults() {
            return DEFAULTS;
        }

        @Override
        public String check(Map<String, String> options, Path baseDir) {
            try {
                SafePaths.inside(baseDir, options.getOrDefault("out", "audio"));
                return null;
            } catch (IllegalArgumentException e) {
                return "out: " + e.getMessage();
            }
        }

        @Override
        public Tool create(String name, Map<String, String> o, Path dir) {
            SarvamTextToSpeechProvider sarvam = new SarvamTextToSpeechProvider(sarvam(o.get("api_key"), o.get("base_url")));
            String out = o.getOrDefault("out", "audio");
            return tool(name, "Speaks text aloud: saves it as a WAV file and returns the file's path. Arguments: "
                    + "text (string), language (optional, e.g. hi-IN" + (o.containsKey("language") ? "; default " + o.get("language") : "") + ").", args -> {
                String text = text(args, "text");
                byte[] audio = synthesize(sarvam, text, arg(args, "language", o.get("language")), o.get("voice"), o.get("model"));
                Path folder = SafePaths.inside(dir, out);
                Files.createDirectories(folder);
                Path file = folder.resolve("speech-" + System.currentTimeMillis() + "-" + counter.incrementAndGet() + ".wav");
                Files.write(file, audio);
                return "Saved speech to " + dir.toAbsolutePath().normalize().relativize(file);
            });
        }
    }

    @FunctionalInterface
    interface Body {
        String run(Map<String, Object> args) throws Exception;
    }

    private static ToolKind kind(String kindName, Set<String> extra, ToolFactory.Creator creator) {
        Set<String> optional = new java.util.HashSet<>(extra);
        optional.add("base_url");
        return new ToolKind() {
            @Override
            public String name() {
                return kindName;
            }

            @Override
            public Set<String> required() {
                return Set.of("api_key");
            }

            @Override
            public Set<String> optional() {
                return Set.copyOf(optional);
            }

            @Override
            public Set<String> secrets() {
                return Set.of("api_key");
            }

            @Override
            public Map<String, ToolDef.OptionValue> defaults() {
                return DEFAULTS;
            }

            @Override
            public Tool create(String name, Map<String, String> options, Path baseDir) throws Exception {
                return creator.create(name, options, baseDir);
            }
        };
    }

    /** A tool whose argument problems come back as text for the agent, not exceptions. */
    private static Tool tool(String name, String description, Body body) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public String execute(Map<String, Object> args) throws Exception {
                try {
                    return body.run(args == null ? Map.of() : args);
                } catch (MissingArgument | IllegalArgumentException e) {
                    return "Error: " + e.getMessage();
                }
            }
        };
    }

    private static final class MissingArgument extends RuntimeException {
        MissingArgument(String message) {
            super(message, null, false, false);
        }
    }

    private static String text(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || String.valueOf(v).isBlank()) throw new MissingArgument("missing argument " + key);
        return String.valueOf(v);
    }

    private static String arg(Map<String, Object> args, String key, String fallback) {
        Object v = args.get(key);
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v);
    }

    /** "Hindi" → hi-IN; codes pass through. */
    static String code(String language) {
        return LanguageMapper.getLanguageCode(language);
    }
}
