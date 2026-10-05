package com.lumi.vault.business.asset.dto;

import java.time.Instant;

public record AssetUrlResponse(String url, Instant expiresAt) {
}
