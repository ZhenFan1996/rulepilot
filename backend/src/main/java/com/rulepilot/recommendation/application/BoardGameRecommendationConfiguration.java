package com.rulepilot.recommendation.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BoardGameRecommendationProperties.class)
class BoardGameRecommendationConfiguration {}
