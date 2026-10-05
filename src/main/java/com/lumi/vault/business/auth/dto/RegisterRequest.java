package com.lumi.vault.business.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[a-zA-Z0-9_-]{3,32}$", message = "用户名需为 3-32 位字母、数字、下划线或短横线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需为 8-64 位")
        String password,

        @Size(max = 32, message = "昵称最长 32 位")
        String nickname) {
}
