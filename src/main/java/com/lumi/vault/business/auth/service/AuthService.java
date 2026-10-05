package com.lumi.vault.business.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.auth.config.JwtProperties;
import com.lumi.vault.business.auth.dto.LoginRequest;
import com.lumi.vault.business.auth.dto.RegisterRequest;
import com.lumi.vault.business.auth.dto.TokenResponse;
import com.lumi.vault.business.auth.dto.UserResponse;
import com.lumi.vault.business.auth.entity.User;
import com.lumi.vault.business.auth.mapper.UserMapper;
import com.lumi.vault.business.auth.repository.TokenStore;
import com.lumi.vault.business.auth.security.AuthenticatedUser;
import com.lumi.vault.exception.ApiException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final TokenStore tokenStore;
    private final JwtProperties properties;

    public AuthService(UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       JwtTokenService tokenService,
                       TokenStore tokenStore,
                       JwtProperties properties) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.tokenStore = tokenStore;
        this.properties = properties;
    }

    @Transactional
    public TokenResponse register(RegisterRequest request) {
        if (findByUsername(request.username()) != null) {
            throw ApiException.conflict("用户名已存在");
        }
        User user = new User();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname() == null || request.nickname().isBlank()
                ? request.username() : request.nickname());
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("用户名已存在");
        }
        return issueTokens(user);
    }

    public TokenResponse login(LoginRequest request) {
        User user = findByUsername(request.username());
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("用户名或密码错误");
        }
        return issueTokens(user);
    }

    public TokenResponse refresh(String refreshToken) {
        Claims claims = decodeRefreshToken(refreshToken);
        Long userId = tokenStore.findUserIdByRefreshToken(claims.getId());
        if (userId == null || !userId.equals(tokenService.userId(claims))) {
            throw ApiException.unauthorized("刷新令牌已失效");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw ApiException.unauthorized("用户不存在");
        }
        tokenStore.revokeRefreshToken(claims.getId());
        return issueTokens(user);
    }

    public void logout(AuthenticatedUser principal, String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                Claims claims = tokenService.decode(refreshToken);
                if (tokenService.hasType(claims, JwtTokenService.TYPE_REFRESH)) {
                    tokenStore.revokeRefreshToken(claims.getId());
                }
            } catch (JwtException | IllegalArgumentException ignored) {
            }
        }
        if (principal != null) {
            Duration remaining = Duration.between(Instant.now(), principal.expiresAt());
            tokenStore.blacklistAccessToken(principal.tokenId(), remaining);
        }
    }

    public UserResponse currentUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw ApiException.unauthorized("用户不存在");
        }
        return UserResponse.from(user);
    }

    private TokenResponse issueTokens(User user) {
        JwtTokenService.IssuedToken access = tokenService.issueAccessToken(user.getId(), user.getUsername());
        JwtTokenService.IssuedToken refresh = tokenService.issueRefreshToken(user.getId(), user.getUsername());
        tokenStore.saveRefreshToken(refresh.jti(), user.getId(), properties.refreshTtl());
        return new TokenResponse("Bearer", access.value(), refresh.value(),
                properties.accessTtl().toSeconds(), properties.refreshTtl().toSeconds());
    }

    private Claims decodeRefreshToken(String refreshToken) {
        try {
            Claims claims = tokenService.decode(refreshToken);
            if (!tokenService.hasType(claims, JwtTokenService.TYPE_REFRESH)) {
                throw ApiException.unauthorized("令牌类型错误");
            }
            return claims;
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("刷新令牌无效或已过期");
        }
    }

    private User findByUsername(String username) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
    }
}
