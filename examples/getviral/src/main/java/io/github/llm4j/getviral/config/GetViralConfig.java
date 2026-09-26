package io.github.llm4j.getviral.config;

import java.nio.file.Path;
import java.util.Map;

/**
 * Runtime configuration, read from environment variables so the same jar runs as a no-key demo,
 * against Gemini, or fully local on Ollama.
 *
 * <table>
 *   <tr><td>GETVIRAL_MODE</td><td>auto (default) | gemini | ollama | demo</td></tr>
 *   <tr><td>GETVIRAL_MODEL</td><td>model id for agents (default gemini-3.5-flash / ollama/gemma3)</td></tr>
 *   <tr><td>GETVIRAL_JUDGE_MODEL</td><td>model id for eval4j judges (defaults to GETVIRAL_MODEL)</td></tr>
 *   <tr><td>GETVIRAL_PORT</td><td>web studio port (default 7070)</td></tr>
 *   <tr><td>GETVIRAL_DATA_DIR</td><td>where Engram memories live (default ./getviral-data)</td></tr>
 *   <tr><td>GETVIRAL_OFFLINE_APIS</td><td>true = never call public APIs, use recorded samples</td></tr>
 *   <tr><td>GETVIRAL_MAX_REVISIONS</td><td>critic rounds before shipping anyway (default 2)</td></tr>
 *   <tr><td>GETVIRAL_ONNX_MODEL / GETVIRAL_ONNX_TOKENIZER</td><td>local ONNX embeddings (addons)</td></tr>
 *   <tr><td>IG_USER_ID / IG_ACCESS_TOKEN</td><td>Instagram professional account credentials</td></tr>
 *   <tr><td>IG_GRAPH_HOST / IG_GRAPH_VERSION</td><td>graph.instagram.com / v23.0 by default</td></tr>
 * </table>
 */
public record GetViralConfig(
        Mode mode,
        String model,
        String judgeModel,
        int port,
        Path dataDir,
        boolean offlineApis,
        int maxRevisions,
        String onnxModelPath,
        String onnxTokenizerPath,
        String igUserId,
        String igAccessToken,
        String igGraphHost,
        String igGraphVersion) {

    public enum Mode { GEMINI, OLLAMA, DEMO }

    public static GetViralConfig fromEnvironment() {
        return from(System.getenv());
    }

    public static GetViralConfig from(Map<String, String> env) {
        Mode mode = resolveMode(env);
        String defaultModel = switch (mode) {
            case GEMINI -> "gemini-3.5-flash";
            case OLLAMA -> "ollama/gemma3";
            case DEMO -> "demo";
        };
        String model = mode == Mode.DEMO ? "demo" : env.getOrDefault("GETVIRAL_MODEL", defaultModel);
        return new GetViralConfig(
                mode,
                model,
                mode == Mode.DEMO ? "demo" : env.getOrDefault("GETVIRAL_JUDGE_MODEL", model),
                Integer.parseInt(env.getOrDefault("GETVIRAL_PORT", "7070")),
                Path.of(env.getOrDefault("GETVIRAL_DATA_DIR", "getviral-data")),
                Boolean.parseBoolean(env.getOrDefault("GETVIRAL_OFFLINE_APIS", "false")),
                Integer.parseInt(env.getOrDefault("GETVIRAL_MAX_REVISIONS", "2")),
                env.get("GETVIRAL_ONNX_MODEL"),
                env.get("GETVIRAL_ONNX_TOKENIZER"),
                env.get("IG_USER_ID"),
                env.get("IG_ACCESS_TOKEN"),
                env.getOrDefault("IG_GRAPH_HOST", "https://graph.instagram.com"),
                env.getOrDefault("IG_GRAPH_VERSION", "v23.0"));
    }

    private static Mode resolveMode(Map<String, String> env) {
        String raw = env.getOrDefault("GETVIRAL_MODE", "auto").trim().toLowerCase();
        return switch (raw) {
            case "gemini" -> Mode.GEMINI;
            case "ollama" -> Mode.OLLAMA;
            case "demo" -> Mode.DEMO;
            default -> hasText(env.get("GEMINI_API_KEY")) ? Mode.GEMINI : Mode.DEMO;
        };
    }

    public boolean instagramConfigured() {
        return hasText(igUserId) && hasText(igAccessToken);
    }

    /** A copy with a different data directory — handy for isolated test runs. */
    public GetViralConfig withDataDir(Path dir) {
        return new GetViralConfig(mode, model, judgeModel, port, dir, offlineApis, maxRevisions,
                onnxModelPath, onnxTokenizerPath, igUserId, igAccessToken, igGraphHost, igGraphVersion);
    }

    /** A copy that never touches the network for public APIs. */
    public GetViralConfig withOfflineApis(boolean offline) {
        return new GetViralConfig(mode, model, judgeModel, port, dataDir, offline, maxRevisions,
                onnxModelPath, onnxTokenizerPath, igUserId, igAccessToken, igGraphHost, igGraphVersion);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
