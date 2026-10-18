package com.lumi.vault.business.perception.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 特征聚合参数（壁纸适配评分权重与阈值），配置文件/环境变量可调。
 */
@Validated
@ConfigurationProperties(prefix = "lumina.perception")
public record PerceptionProperties(
        @DefaultValue("0.6") double wallpaperThreshold,
        @DefaultValue("0.35") double wallpaperHighResBonus,
        @DefaultValue("0.15") double wallpaperMidResBonus,
        @DefaultValue("0.15") double wallpaperLandscapeBonus,
        @DefaultValue("0.2") double wallpaperSceneryBonus,
        @DefaultValue("0.1") double wallpaperBrightPaletteBonus) {
}
