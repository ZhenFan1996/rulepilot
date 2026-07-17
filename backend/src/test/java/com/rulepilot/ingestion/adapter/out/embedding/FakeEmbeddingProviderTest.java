package com.rulepilot.ingestion.adapter.out.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FakeEmbeddingProviderTest {

    @Test
    void isDeterministicAndKeepsRelatedTextCloser() {
        var provider = new FakeEmbeddingProvider();
        var vectors = provider.embed(List.of(
                "final scoring gives one point per objective",
                "how does final scoring work for objectives",
                "place the board and deal five cards"));

        assertThat(vectors).allMatch(vector -> vector.values().size() == FakeEmbeddingProvider.DIMENSIONS);
        assertThat(provider.embed(List.of("final scoring gives one point per objective")).getFirst())
                .isEqualTo(vectors.getFirst());
        assertThat(cosine(vectors.get(0).values(), vectors.get(1).values()))
                .isGreaterThan(cosine(vectors.get(0).values(), vectors.get(2).values()));
    }

    private double cosine(List<Float> left, List<Float> right) {
        double result = 0;
        for (int index = 0; index < left.size(); index++) result += left.get(index) * right.get(index);
        return result;
    }
}
