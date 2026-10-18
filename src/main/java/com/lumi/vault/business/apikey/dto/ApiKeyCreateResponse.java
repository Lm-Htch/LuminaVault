package com.lumi.vault.business.apikey.dto;

/**
 * 创建结果：secret 是明文密钥，仅此一次返回，之后无法再次读取。
 */
public record ApiKeyCreateResponse(ApiKeyResponse key, String secret) {
}
