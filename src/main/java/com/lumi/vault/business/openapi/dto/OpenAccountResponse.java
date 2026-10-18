package com.lumi.vault.business.openapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * 开放 API：当前调用身份。
 */
public record OpenAccountResponse(
        Long userId,
        String username,
        Long keyId,
        String keyName,
        List<String> scopes,
        Instant expiresAt,
        Instant lastUsedAt) {
}
