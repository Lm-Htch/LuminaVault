package com.lumi.vault.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "lumina.vector")
public record VectorProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("wd14-image") @NotBlank String imageModel) {
}
