package com.lumi.vault.business.agent.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record AgentSearchRequest(
        @Size(max = 500, message = "搜索内容最长 500 字")
        String query,

        @Min(value = 1, message = "limit 至少为 1")
        @Max(value = 50, message = "limit 最多为 50")
        Integer limit,

        @DecimalMin(value = "0.0", message = "minConfidence 不能为负")
        @DecimalMax(value = "1.0", message = "minConfidence 最多为 1")
        Double minConfidence,

        @Size(max = 64, message = "attachmentId 非法")
        String attachmentId) {
}
