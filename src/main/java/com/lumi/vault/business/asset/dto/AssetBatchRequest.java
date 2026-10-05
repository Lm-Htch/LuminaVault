package com.lumi.vault.business.asset.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record AssetBatchRequest(@NotEmpty(message = "assetIds 不能为空") List<Long> assetIds) {
}
