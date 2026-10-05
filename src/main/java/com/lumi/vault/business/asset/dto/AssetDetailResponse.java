package com.lumi.vault.business.asset.dto;

import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.entity.AssetMetadata;

import java.time.Instant;

public record AssetDetailResponse(
        Long id,
        String originalFilename,
        String author,
        String contentType,
        long sizeBytes,
        Integer width,
        Integer height,
        String sha256,
        String description,
        AssetMetadata metadata,
        Long characterId,
        Long folderId,
        Instant createdAt,
        Instant updatedAt,
        String url,
        String thumbnailUrl) {

    public static AssetDetailResponse from(Asset asset, String url, String thumbnailUrl) {
        return new AssetDetailResponse(
                asset.getId(),
                asset.getOriginalFilename(),
                asset.getAuthor(),
                asset.getContentType(),
                asset.getSizeBytes() == null ? 0L : asset.getSizeBytes(),
                asset.getWidth(),
                asset.getHeight(),
                asset.getSha256(),
                asset.getDescription(),
                asset.getMetadata(),
                asset.getCharacterId(),
                asset.getFolderId(),
                asset.getCreatedAt(),
                asset.getUpdatedAt(),
                url,
                thumbnailUrl);
    }
}
