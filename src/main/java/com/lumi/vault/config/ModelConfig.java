package com.lumi.vault.config;

import com.lumi.vault.business.model.config.ModelProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ModelProperties.class, VectorProperties.class})
public class ModelConfig {
}
