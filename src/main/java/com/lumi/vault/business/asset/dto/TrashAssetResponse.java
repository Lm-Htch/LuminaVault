package com.lumi.vault.business.asset.dto;

import com.lumi.vault.business.asset.entity.Asset;

import java.time.Instant;

public record TrashAssetResponse(
        Long id,
        String originalFilename,
        String contentType,
        long sizeBytes,
        Integer width,
        Integer height,
        String description,
        Instant createdAt,
        Instant deletedAt,
        Instant purgeAt) {

    public static TrashAssetResponse from(Asset asset, Instant purgeAt) {
        return new TrashAssetResponse(
                asset.getId(),
                asset.getOriginalFilename(),
                asset.getContentType(),
                asset.getSizeBytes() == null ? 0L : asset.getSizeBytes(),
                asset.getWidth(),
                asset.getHeight(),
                asset.getDescription(),
                asset.getCreatedAt(),
                asset.getDeletedAt(),
                purgeAt);
    }
}
