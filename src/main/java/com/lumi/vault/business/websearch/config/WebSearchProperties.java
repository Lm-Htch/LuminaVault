package com.lumi.vault.business.websearch.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "lumina.web-search")
public record WebSearchProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("saucenao") @NotBlank String provider,
        String apiKey,
        @DefaultValue("https://saucenao.com") @NotBlank String baseUrl,
        String proxy,
        @DefaultValue("0.75") @DecimalMin("0.0") @DecimalMax("1.0") double minSimilarity,
        @DefaultValue("true") boolean autoOnUpload,
        @DefaultValue("2000") @Min(0) int dailyQuota,
        @DefaultValue("2000") @Min(0) long requestDelayMs,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("30s") Duration readTimeout) {

    public boolean available() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
