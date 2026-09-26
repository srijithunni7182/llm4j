package io.github.llm4j.getviral.llm;

import io.github.llm4j.LLMClient;
import io.github.llm4j.getviral.config.GetViralConfig;
import io.github.llm4j.getviral.studio.StudioEvents;
import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.LLMClientFactory;

/**
 * Resolves the logical model names used in {@code getviral.loom} ("studio", "judge") to a real
 * provider: Gemini or Ollama through Loom's {@link DefaultLLMClientFactory}, or the scripted demo
 * model. Every client is metered so the studio can show calls, tokens and latency.
 */
public class StudioModels implements LLMClientFactory {

    private final GetViralConfig config;
    private final StudioEvents events;
    private final long demoPaceMillis;
    private final DefaultLLMClientFactory providers = new DefaultLLMClientFactory();

    public StudioModels(GetViralConfig config, StudioEvents events, long demoPaceMillis) {
        this.config = config;
        this.events = events;
        this.demoPaceMillis = demoPaceMillis;
    }

    @Override
    public LLMClient createClient(String logicalName) {
        boolean judge = "judge".equalsIgnoreCase(logicalName);
        String model = judge ? config.judgeModel() : config.model();
        LLMClient client = config.mode() == GetViralConfig.Mode.DEMO
                ? new DemoLLMClient(demoPaceMillis)
                : providers.createClient(model);
        return new MeteredLLMClient(client, model, events);
    }

    public String describe() {
        return switch (config.mode()) {
            case GEMINI -> "Google Gemini · " + config.model();
            case OLLAMA -> "Ollama (local) · " + config.model();
            case DEMO -> "Demo studio model (scripted, no API key)";
        };
    }
}
