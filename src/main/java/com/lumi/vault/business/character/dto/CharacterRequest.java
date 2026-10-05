package com.lumi.vault.business.character.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CharacterRequest(
        @NotBlank(message = "角色名不能为空")
        @Size(max = 128, message = "角色名最长 128 位")
        String name,

        @Size(max = 20, message = "别名最多 20 个")
        List<@Size(max = 128, message = "别名最长 128 位") String> aliases,

        @Size(max = 128, message = "作品名最长 128 位")
        String series,

        @Size(max = 2000, message = "描述最长 2000 位")
        String description,

        Long referenceAssetId) {
}
