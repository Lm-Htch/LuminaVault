package com.lumi.vault.business.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SearchFavoriteRequest(
        @NotBlank(message = "query 不能为空")
        @Size(max = 500, message = "query 最长 500 字")
        String query,

        @NotEmpty(message = "assetIds 不能为空")
        @Size(max = 100, message = "单次最多保存 100 张")
        List<Long> assetIds) {
}
