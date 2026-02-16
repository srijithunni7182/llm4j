package io.github.llm4j.fairness;

import java.util.Collections;
import java.util.List;

/**
 * No-operation bias monitor that detects no bias. Used as the default implementation to maintain
 * backward compatibility.
 */
public class NoOpBiasMonitor implements BiasMonitor {

    @Override
    public List<BiasEvent> detectBias(String text, BiasContext context) {
        return Collections.emptyList();
    }
}
