package com.lumi.vault.business.websearch.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebSearchProperties.class)
public class WebSearchConfig {
}
