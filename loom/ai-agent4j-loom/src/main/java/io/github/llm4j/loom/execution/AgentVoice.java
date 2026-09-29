package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.tools.LanguageTools;
import io.github.llm4j.loom.tools.SafePaths;
import io.github.llm4j.provider.sarvam.SarvamAudioProvider;
import io.github.llm4j.provider.sarvam.SarvamTextToSpeechProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * An agent's {@code voice { listen speak language voice out }}: tasks given as audio files are heard
 * (transcribed) and answers are spoken to WAV files. Files stay inside the script's directory.
 */
final class AgentVoice {

    static final String PREFIX = "sarvam/";
    static final Set<String> AUDIO = Set.of("wav", "mp3", "ogg", "flac", "m4a", "aac", "webm");

    private final AgentDef.VoiceConfig config;
    private final Path baseDir;
    private final SarvamAudioProvider stt;
    private final SarvamTextToSpeechProvider tts;

    AgentVoice(AgentDef.VoiceConfig config, Function<String, String> env, Path baseDir) {
        this.config = config;
        this.baseDir = baseDir;
        var sarvam = LanguageTools.sarvam(env.apply("SARVAM_API_KEY"), env.apply("SARVAM_BASE_URL"));
        this.stt = config.getListen() == null ? null : new SarvamAudioProvider(sarvam);
        this.tts = config.getSpeak() == null ? null : new SarvamTextToSpeechProvider(sarvam);
    }

    boolean speaks() {
        return tts != null;
    }

    /** The audio file a task names, when this agent listens and the task is just such a path; else null. */
    Path audioTask(String task) {
        if (stt == null || task == null) return null;
        String t = task.strip();
        int dot = t.lastIndexOf('.');
        if (dot < 0 || t.contains("\n") || !AUDIO.contains(t.substring(dot + 1).toLowerCase(Locale.ROOT))) return null;
        try {
            Path p = SafePaths.inside(baseDir, t);
            return Files.isRegularFile(p) ? p : null;
        } catch (IllegalArgumentException outside) {
            return null;
        }
    }

    String transcribe(Path audio) {
        return LanguageTools.transcribe(stt, audio, config.getLanguage(), model(config.getListen()), false);
    }

    /** Speaks {@code text} to {@code <out>/<stem>.wav}; returns the file's path relative to the script's directory. */
    String speak(String text, String stem) throws IOException {
        byte[] audio = LanguageTools.synthesize(tts, text, config.getLanguage(), config.getVoice(), model(config.getSpeak()));
        Path folder = SafePaths.inside(baseDir, config.getOut());
        Files.createDirectories(folder);
        Path file = folder.resolve(stem + ".wav");
        Files.write(file, audio);
        return baseDir.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString();
    }

    static String model(String written) {
        return written.substring(PREFIX.length());
    }
}
