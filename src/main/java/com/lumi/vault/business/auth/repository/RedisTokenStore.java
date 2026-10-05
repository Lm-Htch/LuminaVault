package com.lumi.vault.business.auth.repository;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

@Repository
public class RedisTokenStore implements TokenStore {

    private static final String REFRESH_PREFIX = "auth:refresh:";
    private static final String ACCESS_BLACKLIST_PREFIX = "auth:blacklist:access:";

    private final StringRedisTemplate redis;

    public RedisTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void saveRefreshToken(String jti, Long userId, Duration ttl) {
        redis.opsForValue().set(REFRESH_PREFIX + jti, userId.toString(), ttl);
    }

    @Override
    public Long findUserIdByRefreshToken(String jti) {
        String value = redis.opsForValue().get(REFRESH_PREFIX + jti);
        return value == null ? null : Long.valueOf(value);
    }

    @Override
    public void revokeRefreshToken(String jti) {
        redis.delete(REFRESH_PREFIX + jti);
    }

    @Override
    public void blacklistAccessToken(String jti, Duration ttl) {
        if (ttl != null && !ttl.isNegative() && !ttl.isZero()) {
            redis.opsForValue().set(ACCESS_BLACKLIST_PREFIX + jti, "1", ttl);
        }
    }

    @Override
    public boolean isAccessTokenBlacklisted(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(ACCESS_BLACKLIST_PREFIX + jti));
    }
}
