package com.lumi.vault.business.websearch.dto;

import java.util.List;

public record WebSourceResult(
        String provider,
        double similarity,
        String sourceType,
        String sourceUrl,
        String sourceId,
        String title,
        String author,
        List<String> characters,
        String raw) {
}
