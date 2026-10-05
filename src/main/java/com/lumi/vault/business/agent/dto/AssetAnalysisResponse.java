package com.lumi.vault.business.agent.dto;

import java.util.List;

public record AssetAnalysisResponse(
        Long assetId,
        String description,
        List<String> tags,
        String status,
        String model,
        String error) {
}
