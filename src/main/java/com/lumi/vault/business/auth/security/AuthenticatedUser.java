package com.lumi.vault.business.auth.security;

import java.time.Instant;

public record AuthenticatedUser(Long id, String username, String tokenId, Instant expiresAt) {
}
