package io.github.llm4j.loom.knowledge;

import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.embedding.GeminiEmbeddingProvider;
import io.github.llm4j.config.LLMConfig;
import java.util.function.Function;

/**
 * {@code gemini/<model>} (needs GEMINI_API_KEY); {@code onnx/<model.onnx>|<tokenizer.json>} and
 * {@code djl/<model url>} when the ai-agent4j-addons module is on the classpath.
 */
public final class DefaultEmbeddingFactory implements EmbeddingFactory {

    private static final String ONNX = "io.github.llm4j.agent.rag.embedding.OnnxEmbeddingProvider";
    private static final String DJL = "io.github.llm4j.agent.rag.embedding.DjlEmbeddingProvider";

    private final Function<String, String> env;
    private final io.github.llm4j.secret.SecretStore secrets;

    public DefaultEmbeddingFactory(Function<String, String> env) {
        this(env, null);
    }

    /** @param secrets where the Gemini key is looked up first (as {@code GEMINI_API_KEY}), fetched for each request; null for none */
    public DefaultEmbeddingFactory(Function<String, String> env, io.github.llm4j.secret.SecretStore secrets) {
        this.env = env;
        this.secrets = secrets;
    }

    @Override
    public String problem(String model) {
        if (model == null || model.isBlank()) return "needs embedding: \"<model>\", e.g. \"gemini/text-embedding-004\"";
        String prefix = model.contains("/") ? model.substring(0, model.indexOf('/')) : "";
        switch (prefix) {
            case "gemini":
                String key = env.apply("GEMINI_API_KEY");
                boolean stored = secrets != null && secrets.contains("GEMINI_API_KEY");
                return !stored && (key == null || key.isBlank()) ? "embedding " + model + " needs GEMINI_API_KEY (an environment variable or a secret)" : null;
            case "onnx":
                if (!onClasspath(ONNX)) return "embedding " + model + " needs the ai-agent4j-addons module on the classpath";
                return model.substring(5).contains("|") ? null : "onnx embeddings are written onnx/<model.onnx>|<tokenizer.json>";
            case "djl":
                return onClasspath(DJL) ? null : "embedding " + model + " needs the ai-agent4j-addons module on the classpath";
            default:
                return "unknown embedding model \"" + model + "\"; use gemini/<model>, onnx/<model>|<tokenizer> or djl/<url>";
        }
    }

    @Override
    public EmbeddingProvider create(String model) throws Exception {
        String p = problem(model);
        if (p != null) throw new IllegalArgumentException(p);
        if (model.startsWith("gemini/")) {
            LLMConfig.Builder builder = LLMConfig.builder();
            if (secrets != null && secrets.contains("GEMINI_API_KEY")) builder.apiKey(io.github.llm4j.secret.SecretRef.of(secrets, "GEMINI_API_KEY"));
            else builder.apiKey(env.apply("GEMINI_API_KEY"));
            LLMConfig config = builder.build();
            return new GeminiEmbeddingProvider(config, model.substring("gemini/".length()));
        }
        if (model.startsWith("onnx/")) {
            String[] parts = model.substring(5).split("\\|", 2);
            return (EmbeddingProvider) Class.forName(ONNX).getConstructor(String.class, String.class)
                    .newInstance(parts[0], parts[1]);
        }
        return (EmbeddingProvider) Class.forName(DJL).getConstructor(String.class).newInstance(model.substring(4));
    }

    private static boolean onClasspath(String cls) {
        try {
            Class.forName(cls, false, DefaultEmbeddingFactory.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
