package com.lumi.vault.business.asset.dto;

import jakarta.validation.constraints.Size;

public record UpdateAssetRequest(
        @Size(max = 512, message = "文件名最长 512 位") String originalFilename,
        @Size(max = 255, message = "作者最长 255 位") String author,
        @Size(max = 1024, message = "描述最长 1024 位") String description) {
}
