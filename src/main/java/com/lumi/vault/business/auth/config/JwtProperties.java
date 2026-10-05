package com.lumi.vault.business.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "lumina.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "JWT 密钥长度至少 32 字符") String secret,
        @DefaultValue("30m") Duration accessTtl,
        @DefaultValue("14d") Duration refreshTtl,
        @DefaultValue("LuminaVault") String issuer) {
}
