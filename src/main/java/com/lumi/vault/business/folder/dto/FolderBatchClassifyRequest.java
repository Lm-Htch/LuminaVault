package com.lumi.vault.business.folder.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record FolderBatchClassifyRequest(@NotEmpty(message = "assetIds 不能为空") List<Long> assetIds) {
}
