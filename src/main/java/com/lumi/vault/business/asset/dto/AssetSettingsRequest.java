package com.lumi.vault.business.asset.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AssetSettingsRequest(
        @NotNull(message = "保留天数不能为空")
        @Min(value = 1, message = "保留天数至少 1 天")
        @Max(value = 3650, message = "保留天数最多 3650 天")
        Integer retentionDays) {
}
