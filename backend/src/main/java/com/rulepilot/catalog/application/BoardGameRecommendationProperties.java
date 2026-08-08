package com.rulepilot.catalog.application;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rulepilot.bgg.recommendation-agent")
public record BoardGameRecommendationProperties(
        int candidatePoolSize,
        int modelCandidateLimit,
        int resultCount,
        BigDecimal diversityOverlapLimit) {

    public BoardGameRecommendationProperties {
        if (candidatePoolSize < 3 || candidatePoolSize > 20) {
            throw new IllegalArgumentException("recommendation candidate pool size must be between 3 and 20");
        }
        if (resultCount < 1 || resultCount > 5 || resultCount > candidatePoolSize) {
            throw new IllegalArgumentException("recommendation result count is invalid");
        }
        if (modelCandidateLimit < resultCount || modelCandidateLimit > candidatePoolSize) {
            throw new IllegalArgumentException("recommendation model candidate limit is invalid");
        }
        if (diversityOverlapLimit == null
                || diversityOverlapLimit.compareTo(BigDecimal.ZERO) < 0
                || diversityOverlapLimit.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("recommendation diversity overlap limit must be between 0 and 1");
        }
    }
}
