package com.rulepilot.ingestion;

import java.util.List;

public interface EmbeddingProvider {

    String id();

    int dimensions();

    default int batchSize() {
        return 32;
    }

    List<EmbeddingVector> embed(List<String> texts);

    record EmbeddingVector(List<Float> values) {
        public EmbeddingVector {
            values = List.copyOf(values);
            if (values.isEmpty() || values.stream().anyMatch(value -> value == null || !Float.isFinite(value))) {
                throw new IllegalArgumentException("embedding values must be finite");
            }
        }
    }
}
