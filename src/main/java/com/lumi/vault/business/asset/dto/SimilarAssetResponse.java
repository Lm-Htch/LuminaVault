package com.lumi.vault.business.asset.dto;

public record SimilarAssetResponse(
        Long assetId,
        String originalFilename,
        String sha256,
        double similarity) {
}
