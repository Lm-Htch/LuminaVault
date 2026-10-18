package com.lumi.vault.business.websearch.dto;

import java.util.List;

public record WebSourceResponse(
        String provider,
        String status,
        Double similarity,
        String sourceType,
        String sourceUrl,
        String sourceId,
        String title,
        String author,
        List<String> characters) {
}
