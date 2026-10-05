package com.lumi.vault.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "lumina.storage")
public record StorageProperties(
        @NotNull URI endpoint,
        @DefaultValue("us-east-1") String region,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        @NotBlank String bucket,
        @DefaultValue("true") boolean forcePathStyle,
        @DefaultValue("7d") Duration presignExpiry) {
}
