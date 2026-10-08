package io.github.llm4j.loom.parity;

import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A deterministic, offline embedding for tests: a bag of words (lower-cased, trailing "s" dropped) hashed
 * into 256 dimensions and L2-normalised. Texts that share words are close. Counts every text embedded.
 */
public final class HashingEmbeddingProvider implements EmbeddingProvider {

    public final AtomicInteger embedded = new AtomicInteger();

    @Override
    public float[] embed(String text) {
        embedded.incrementAndGet();
        float[] v = new float[256];
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() < 3) continue;
            if (w.endsWith("s")) w = w.substring(0, w.length() - 1);
            v[Math.floorMod(w.hashCode(), 256)] += 1;
        }
        double n = 0;
        for (float x : v) n += x * x;
        n = Math.sqrt(n);
        if (n > 0) for (int i = 0; i < v.length; i++) v[i] /= (float) n;
        return v;
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        for (String t : texts) out.add(embed(t));
        return out;
    }

    @Override
    public int getDimensions() {
        return 256;
    }
}
