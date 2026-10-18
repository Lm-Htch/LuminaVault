package com.lumi.vault.business.agent.dto;

import com.lumi.vault.business.agent.entity.SearchHistory;

import java.time.Instant;

public record SearchHistoryResponse(Long id, String query, Instant searchedAt) {

    public static SearchHistoryResponse from(SearchHistory history) {
        return new SearchHistoryResponse(history.getId(), history.getQueryText(), history.getCreatedAt());
    }
}
