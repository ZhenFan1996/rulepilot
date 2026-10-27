package com.rulepilot.recommendation.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rulepilot.bgg.recommendation-agent")
public record BoardGameRecommendationProperties(Duration timeout) {
    public BoardGameRecommendationProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("recommendation timeout must be positive");
        }
    }
}
