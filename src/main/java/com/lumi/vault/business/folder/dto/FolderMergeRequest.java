package com.lumi.vault.business.folder.dto;

import jakarta.validation.constraints.NotNull;

public record FolderMergeRequest(@NotNull(message = "targetFolderId 不能为空") Long targetFolderId) {
}
