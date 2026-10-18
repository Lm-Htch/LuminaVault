package com.lumi.vault.business.asset.dto;

import com.lumi.vault.business.asset.entity.Asset;

import java.time.Instant;
import java.util.List;

public record AssetResponse(
        Long id,
        String originalFilename,
        String author,
        String contentType,
        long sizeBytes,
        Integer width,
        Integer height,
        String sha256,
        String description,
        Long characterId,
        List<Long> folderIds,
        Instant createdAt,
        Instant updatedAt) {

    public static AssetResponse from(Asset asset, List<Long> folderIds) {
        return new AssetResponse(
                asset.getId(),
                asset.getOriginalFilename(),
                asset.getAuthor(),
                asset.getContentType(),
                asset.getSizeBytes() == null ? 0L : asset.getSizeBytes(),
                asset.getWidth(),
                asset.getHeight(),
                asset.getSha256(),
                asset.getDescription(),
                asset.getCharacterId(),
                folderIds == null ? List.of() : folderIds,
                asset.getCreatedAt(),
                asset.getUpdatedAt());
    }
}
