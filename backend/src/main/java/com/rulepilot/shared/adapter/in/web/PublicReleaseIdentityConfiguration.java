package com.rulepilot.shared.adapter.in.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReleaseIdentityProperties.class)
class PublicReleaseIdentityConfiguration {}
