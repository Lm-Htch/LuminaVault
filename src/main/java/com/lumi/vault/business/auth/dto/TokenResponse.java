package com.lumi.vault.business.auth.dto;

public record TokenResponse(
        String tokenType,
        String accessToken,
        String refreshToken,
        long accessExpiresIn,
        long refreshExpiresIn) {
}
