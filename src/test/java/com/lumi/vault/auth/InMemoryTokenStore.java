package com.lumi.vault.auth;

import com.lumi.vault.business.auth.repository.TokenStore;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryTokenStore implements TokenStore {

    private final Map<String, Long> refreshTokens = new ConcurrentHashMap<>();
    private final Map<String, Boolean> blacklistedAccessTokens = new ConcurrentHashMap<>();

    @Override
    public void saveRefreshToken(String jti, Long userId, Duration ttl) {
        refreshTokens.put(jti, userId);
    }

    @Override
    public Long findUserIdByRefreshToken(String jti) {
        return refreshTokens.get(jti);
    }

    @Override
    public void revokeRefreshToken(String jti) {
        refreshTokens.remove(jti);
    }

    @Override
    public void blacklistAccessToken(String jti, Duration ttl) {
        if (ttl != null && !ttl.isNegative() && !ttl.isZero()) {
            blacklistedAccessTokens.put(jti, Boolean.TRUE);
        }
    }

    @Override
    public boolean isAccessTokenBlacklisted(String jti) {
        return blacklistedAccessTokens.containsKey(jti);
    }
}
