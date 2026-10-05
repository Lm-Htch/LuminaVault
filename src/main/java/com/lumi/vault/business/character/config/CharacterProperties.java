package com.lumi.vault.business.character.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "lumina.character")
public record CharacterProperties(
        @DefaultValue("true") boolean autoMatch,
        @DefaultValue("false") boolean autoCreate,
        @DefaultValue("0.78") @DecimalMin("0.0") @DecimalMax("1.0") double matchThreshold,
        @DefaultValue("0.02") @DecimalMin("0.0") @DecimalMax("1.0") double matchMargin,
        @DefaultValue("0.3") @DecimalMin("0.0") @DecimalMax("1.0") double feedbackReferenceWeight) {
}
