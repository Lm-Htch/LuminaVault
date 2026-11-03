package com.lumi.vault.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "lumina.vector")
public record VectorProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("wd14-image") @NotBlank String imageModel,
        /**
         * 域判别阈值：WD14 打出的标签少于该数量时，判定为通用域（真人 / 风景 / 截图），
         * 标签主源改用多模态模型、相似建边只走 CLIP 通道。设 0 关闭判别，全部按动漫域处理。
         */
        @DefaultValue("4") int generalDomainTagThreshold) {
}
