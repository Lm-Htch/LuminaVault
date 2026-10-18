package com.lumi.vault.business.asset.dto;

/**
 * 资产原始字节（供开放 API / MCP 直接取图，避免依赖预签名 URL 的可达性）。
 */
public record AssetContentResponse(byte[] bytes, String contentType, String filename) {
}
