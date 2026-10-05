package com.lumi.vault.business.auth.service;

import com.lumi.vault.business.auth.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtTokenService {

    public static final String CLAIM_TYPE = "typ";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
    private static final String CLAIM_USERNAME = "username";

    private final SecretKey key;
    private final JwtProperties properties;

    public JwtTokenService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public record IssuedToken(String value, String jti, Instant expiresAt) {
    }

    public IssuedToken issueAccessToken(Long userId, String username) {
        return issue(userId, username, TYPE_ACCESS, properties.accessTtl());
    }

    public IssuedToken issueRefreshToken(Long userId, String username) {
        return issue(userId, username, TYPE_REFRESH, properties.refreshTtl());
    }

    private IssuedToken issue(Long userId, String username, String type, Duration ttl) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        String jti = UUID.randomUUID().toString();
        String value = Jwts.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim(CLAIM_TYPE, type)
                .claim(CLAIM_USERNAME, username)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(value, jti, expiresAt);
    }

    public Claims decode(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean hasType(Claims claims, String type) {
        return type.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public Long userId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    public String username(Claims claims) {
        return claims.get(CLAIM_USERNAME, String.class);
    }
}
