package com.lumi.vault.business.auth.repository;

import java.time.Duration;

public interface TokenStore {

    void saveRefreshToken(String jti, Long userId, Duration ttl);

    Long findUserIdByRefreshToken(String jti);

    void revokeRefreshToken(String jti);

    void blacklistAccessToken(String jti, Duration ttl);

    boolean isAccessTokenBlacklisted(String jti);
}
