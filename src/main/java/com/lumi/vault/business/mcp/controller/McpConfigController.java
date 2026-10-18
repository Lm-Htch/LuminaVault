package com.lumi.vault.business.mcp.controller;

import com.lumi.vault.business.mcp.dto.McpConfigResponse;
import com.lumi.vault.business.mcp.service.McpConfigService;
import com.lumi.vault.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 接入指引（仅登录用户可读：会暴露本机部署路径，API 密钥不能读取）。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpConfigController {

    private final McpConfigService mcpConfigService;

    public McpConfigController(McpConfigService mcpConfigService) {
        this.mcpConfigService = mcpConfigService;
    }

    @GetMapping("/config")
    public Result<McpConfigResponse> config() {
        return Result.ok(mcpConfigService.describe());
    }
}
