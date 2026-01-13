package com.future.kakaomcp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EnrichProperties.class)
public class EnrichConfig {
}
