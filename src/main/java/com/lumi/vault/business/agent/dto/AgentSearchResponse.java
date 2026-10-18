package com.lumi.vault.business.agent.dto;

import java.util.List;

public record AgentSearchResponse(
        String query,
        List<AgentSearchResult> results,
        String comment,
        boolean aiEnabled,
        List<String> keywords,
        List<String> colors,
        List<String> excluded,
        List<String> filters) {
}
