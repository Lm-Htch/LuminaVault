package com.lumi.vault.business.openapi.dto;

/**
 * 开放 API：图库概览统计。
 */
public record OpenStatsResponse(
        long assets,
        long trash,
        long folders,
        long characters,
        long similarityEdges) {
}
