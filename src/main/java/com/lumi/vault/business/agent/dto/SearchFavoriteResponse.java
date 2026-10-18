package com.lumi.vault.business.agent.dto;

import java.time.Instant;
import java.util.List;

public record SearchFavoriteResponse(Long id, String query, List<Long> assetIds, Instant createdAt) {
}
