package com.lumi.vault.business.asset.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "lumina.asset")
public record AssetProperties(
        @DefaultValue("30") @Min(1) @Max(3650) int trashRetentionDays,
        @DefaultValue("0 0 4 * * *") String purgeCron,
        @DefaultValue("480") @Min(64) @Max(4096) int thumbnailMaxSize) {
}
