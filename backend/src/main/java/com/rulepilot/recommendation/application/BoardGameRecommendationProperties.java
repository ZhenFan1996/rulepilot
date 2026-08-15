package com.rulepilot.recommendation.application;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rulepilot.bgg.recommendation-agent")
public record BoardGameRecommendationProperties(
        int modelCandidateLimit,
        int resultCount,
        BigDecimal diversityOverlapLimit,
        Duration timeout) {

    public BoardGameRecommendationProperties {
        if (resultCount < 1 || resultCount > 5) {
            throw new IllegalArgumentException("recommendation result count is invalid");
        }
        if (modelCandidateLimit < resultCount || modelCandidateLimit > 20) {
            throw new IllegalArgumentException("recommendation model candidate limit is invalid");
        }
        if (diversityOverlapLimit == null
                || diversityOverlapLimit.compareTo(BigDecimal.ZERO) < 0
                || diversityOverlapLimit.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("recommendation diversity overlap limit must be between 0 and 1");
        }
        if (timeout == null
                || timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(20)) > 0) {
            throw new IllegalArgumentException("recommendation timeout must be positive and no longer than 20 seconds");
        }
    }
}
