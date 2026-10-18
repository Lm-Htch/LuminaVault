package com.lumi.vault.business.agent.dto;

import java.util.List;

public record AgentSearchResult(
        Long assetId,
        String sha256,
        String originalFilename,
        double confidence,
        String description,
        List<String> tags,
        Long characterId,
        String characterName,
        boolean verified) {
}
