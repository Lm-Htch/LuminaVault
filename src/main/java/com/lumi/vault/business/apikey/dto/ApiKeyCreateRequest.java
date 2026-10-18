package com.lumi.vault.business.apikey.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建密钥。scopes 省略时默认 read+write；expiresInDays 省略表示永不过期。
 */
public record ApiKeyCreateRequest(
        @NotBlank(message = "密钥名称不能为空")
        @Size(max = 100, message = "密钥名称最长 100 位")
        String name,

        List<String> scopes,

        @Min(value = 1, message = "有效期至少 1 天")
        @Max(value = 3650, message = "有效期最多 3650 天")
        Integer expiresInDays) {
}
