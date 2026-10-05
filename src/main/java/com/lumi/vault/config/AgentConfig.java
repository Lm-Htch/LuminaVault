package com.lumi.vault.config;

import com.lumi.vault.business.agent.config.AiProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableConfigurationProperties(AiProperties.class)
public class AgentConfig {
}
