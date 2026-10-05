package com.lumi.vault.business.folder.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record FolderBatchAssignRequest(@NotEmpty(message = "assetIds 不能为空") List<Long> assetIds, Long folderId) {
}
