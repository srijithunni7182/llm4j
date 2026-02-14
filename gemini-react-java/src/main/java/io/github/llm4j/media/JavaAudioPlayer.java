package io.github.llm4j.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CountDownLatch;

/**
 * Default implementation of {@link AudioPlayer} using Java Sound API.
 * Caches audio data to temporary files.
 */
public class JavaAudioPlayer implements AudioPlayer {

    private static final Logger logger = LoggerFactory.getLogger(JavaAudioPlayer.class);
    private final Path cacheDir;

    public JavaAudioPlayer() {
        this(Paths.get(System.getProperty("java.io.tmpdir"), "llm4j-audio-cache"));
    }

    public JavaAudioPlayer(Path cacheDir) {
        this.cacheDir = cacheDir;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create audio cache directory: " + cacheDir, e);
        }
    }

    @Override
    public void play(byte[] audioData) {
        play(audioData, "default");
    }

    @Override
    public void play(byte[] audioData, String sessionId) {
        if (audioData == null || audioData.length == 0)
            return;

        try {
            String hash = computeHash(audioData);
            // Create session-specific subdirectory
            Path sessionDir = cacheDir.resolve(sessionId != null ? sessionId : "default");
            if (!Files.exists(sessionDir)) {
                Files.createDirectories(sessionDir);
            }

            Path cachedFile = sessionDir.resolve(hash + ".wav");

            if (!Files.exists(cachedFile)) {
                logger.debug("Caching audio to {}", cachedFile);
                Files.write(cachedFile, audioData);
            } else {
                logger.debug("Playing from cache: {}", cachedFile);
            }

            play(cachedFile.toFile());

        } catch (Exception e) {
            logger.error("Failed to process audio data for playback", e);
        }
    }

    @Override
    public void play(File audioFile) {
        if (audioFile == null || !audioFile.exists()) {
            logger.warn("Audio file not found: {}", audioFile);
            return;
        }

        try (AudioInputStream audioStream = AudioSystem.getAudioInputStream(audioFile)) {
            DataLine.Info info = new DataLine.Info(Clip.class, audioStream.getFormat());

            if (!AudioSystem.isLineSupported(info)) {
                logger.warn("Audio format not supported: {}", audioStream.getFormat());
                return;
            }

            Clip clip = (Clip) AudioSystem.getLine(info);
            clip.open(audioStream);

            CountDownLatch latch = new CountDownLatch(1);
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    latch.countDown();
                }
            });

            clip.start();
            latch.await(); // Block until playback finishes
            clip.close();

        } catch (Exception e) {
            logger.error("Failed to play audio file: {}", audioFile, e);
        }
    }

    private String computeHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(data);
            return bytesToHex(encodedhash);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(java.util.Arrays.hashCode(data));
        }
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
