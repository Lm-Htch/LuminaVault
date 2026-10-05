package com.lumi.vault.business.folder.dto;

import com.lumi.vault.business.folder.entity.Folder;

import java.time.Instant;

public record FolderResponse(
        Long id,
        String name,
        String description,
        boolean autoCreated,
        long assetCount,
        Instant createdAt) {

    public static FolderResponse of(Folder folder, long assetCount) {
        return new FolderResponse(
                folder.getId(),
                folder.getName(),
                folder.getDescription(),
                Boolean.TRUE.equals(folder.getAutoCreated()),
                assetCount,
                folder.getCreatedAt());
    }
}
