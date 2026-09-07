package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rulepilot.bgg.recommendation-agent")
public record BoardGameRecommendationProperties(
        int modelCandidateLimit,
        BigDecimal diversityOverlapLimit,
        Duration timeout) {

    public BoardGameRecommendationProperties {
        if (modelCandidateLimit < 1
                || modelCandidateLimit > BoardGameRecommendationCatalog.MAX_SEARCH_PAGE_SIZE) {
            throw new IllegalArgumentException("recommendation model candidate limit is invalid");
        }
        if (diversityOverlapLimit == null
                || diversityOverlapLimit.compareTo(BigDecimal.ZERO) < 0
                || diversityOverlapLimit.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("recommendation diversity overlap limit must be between 0 and 1");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("recommendation timeout must be positive");
        }
    }
}
