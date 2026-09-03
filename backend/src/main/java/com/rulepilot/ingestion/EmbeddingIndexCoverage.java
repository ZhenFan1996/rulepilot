package com.rulepilot.ingestion;

public record EmbeddingIndexCoverage(long totalChunks, long indexedChunks) {

    public EmbeddingIndexCoverage {
        if (totalChunks < 0 || indexedChunks < 0 || indexedChunks > totalChunks) {
            throw new IllegalArgumentException("embedding index coverage is invalid");
        }
    }

    public boolean complete() {
        return totalChunks > 0 && indexedChunks == totalChunks;
    }
}
