package com.lumi.vault.config;

import com.lumi.vault.business.asset.config.AssetProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(AssetProperties.class)
public class AssetConfig {
}
