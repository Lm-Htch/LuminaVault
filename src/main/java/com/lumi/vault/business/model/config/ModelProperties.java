package com.lumi.vault.business.model.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "lumina.model")
public record ModelProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://localhost:8000") @NotBlank String baseUrl,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("120s") Duration readTimeout,
        @DefaultValue("0.5") @DecimalMin("0.0") @DecimalMax("1.0") double tagConfidence) {
}
