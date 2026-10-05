package com.lumi.vault.business.folder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FolderRequest(
        @NotBlank(message = "文件夹名不能为空")
        @Size(max = 128, message = "文件夹名最长 128 位")
        String name,

        @Size(max = 512, message = "描述最长 512 位")
        String description) {
}
