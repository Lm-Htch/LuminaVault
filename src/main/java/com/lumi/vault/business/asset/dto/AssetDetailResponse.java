package com.lumi.vault.business.asset.dto;

import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.entity.AssetMetadata;

import java.time.Instant;
import java.util.List;

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
        Double characterConfidence,
        List<Long> folderIds,
        Instant createdAt,
        Instant updatedAt,
        String url,
        String thumbnailUrl) {

    public static AssetDetailResponse from(Asset asset, String url, String thumbnailUrl, List<Long> folderIds) {
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
                asset.getCharacterConfidence(),
                folderIds == null ? List.of() : folderIds,
                asset.getCreatedAt(),
                asset.getUpdatedAt(),
                url,
                thumbnailUrl);
    }
}
