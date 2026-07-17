package com.rulepilot.ingestion.adapter.out.embedding;

import com.rulepilot.ingestion.EmbeddingProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "rulepilot.embedding.provider", havingValue = "fake", matchIfMissing = true)
public class FakeEmbeddingProvider implements EmbeddingProvider {

    public static final int DIMENSIONS = 64;

    @Override
    public String id() {
        return "fake-hash-v1";
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public List<EmbeddingVector> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("embedding input is required");
        }
        return texts.stream().map(this::embedOne).toList();
    }

    private EmbeddingVector embedOne(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("embedding text is required");
        }
        double[] values = new double[DIMENSIONS];
        String normalized = text.toLowerCase(Locale.ROOT).strip();
        List<String> features = new ArrayList<>();
        for (String token : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (!token.isBlank()) features.add("word:" + token);
        }
        int[] codePoints = normalized.codePoints().toArray();
        for (int index = 0; index + 2 < codePoints.length; index++) {
            features.add("tri:" + new String(codePoints, index, 3));
        }
        for (String feature : features) {
            int hash = feature.hashCode();
            int bucket = Math.floorMod(hash, DIMENSIONS);
            values[bucket] += (hash & 1) == 0 ? 1.0 : -1.0;
        }
        double norm = Math.sqrt(java.util.Arrays.stream(values).map(value -> value * value).sum());
        List<Float> normalizedValues = new ArrayList<>(DIMENSIONS);
        for (double value : values) normalizedValues.add((float) (value / Math.max(norm, 1.0)));
        return new EmbeddingVector(normalizedValues);
    }
}
