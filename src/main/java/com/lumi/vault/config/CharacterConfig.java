package com.lumi.vault.config;

import com.lumi.vault.business.character.config.CharacterProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CharacterProperties.class)
public class CharacterConfig {
}
