package com.lumi.vault.business.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentSearchRequest(
        @NotBlank(message = "搜索内容不能为空")
        @Size(max = 500, message = "搜索内容最长 500 字")
        String query,

        @Min(value = 1, message = "limit 至少为 1")
        @Max(value = 50, message = "limit 最多为 50")
        Integer limit) {
}
