package com.lumi.vault.business.apikey.dto;

import com.lumi.vault.business.apikey.entity.ApiKey;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public record ApiKeyResponse(
        Long id,
        String name,
        String keyPrefix,
        List<String> scopes,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant revokedAt,
        boolean active) {

    public static ApiKeyResponse from(ApiKey key, boolean active) {
        return new ApiKeyResponse(
                key.getId(),
                key.getName(),
                key.getKeyPrefix(),
                splitScopes(key.getScopes()),
                key.getCreatedAt(),
                key.getExpiresAt(),
                key.getLastUsedAt(),
                key.getRevokedAt(),
                active);
    }

    public static List<String> splitScopes(String scopes) {
        if (scopes == null || scopes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(scopes.split(","))
                .map(String::trim)
                .filter(scope -> !scope.isEmpty())
                .toList();
    }
}
