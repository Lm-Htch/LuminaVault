package com.lumi.vault.business.apikey.controller;

import com.lumi.vault.business.apikey.dto.ApiKeyCreateRequest;
import com.lumi.vault.business.apikey.dto.ApiKeyCreateResponse;
import com.lumi.vault.business.apikey.dto.ApiKeyResponse;
import com.lumi.vault.business.apikey.service.ApiKeyService;
import com.lumi.vault.business.auth.security.AuthenticatedUser;
import com.lumi.vault.common.Result;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 网站侧的密钥管理（仅登录用户可用，API 密钥自身不能签发新密钥）。
 */
@RestController
@RequestMapping("/api/keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @GetMapping
    public Result<List<ApiKeyResponse>> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return Result.ok(apiKeyService.list(principal.id()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<ApiKeyCreateResponse> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                               @Valid @RequestBody ApiKeyCreateRequest request) {
        return Result.ok(apiKeyService.create(principal.id(), request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> revoke(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable Long id) {
        apiKeyService.revoke(principal.id(), id);
        return Result.ok();
    }
}
