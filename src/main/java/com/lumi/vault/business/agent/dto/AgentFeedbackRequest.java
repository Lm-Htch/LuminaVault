package com.lumi.vault.business.agent.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AgentFeedbackRequest(
        @NotNull(message = "assetId 不能为空")
        Long assetId,

        @NotBlank(message = "action 不能为空")
        @Pattern(regexp = "SELECT|REJECT", message = "action 只能是 SELECT 或 REJECT")
        String action,

        @Size(max = 1000, message = "query 最长 1000 字")
        String query,

        @Size(max = 2000, message = "description 最长 2000 字")
        String description,

        @PositiveOrZero(message = "weight 不能为负数")
        @DecimalMax(value = "10.0", message = "weight 最大为 10")
        Double weight) {
}
