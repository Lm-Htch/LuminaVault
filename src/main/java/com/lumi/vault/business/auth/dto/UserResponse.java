package com.lumi.vault.business.auth.dto;

import com.lumi.vault.business.auth.entity.User;

import java.time.Instant;

public record UserResponse(Long id, String username, String nickname, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getNickname(), user.getCreatedAt());
    }
}
