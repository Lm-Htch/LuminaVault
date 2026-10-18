package com.lumi.vault.business.mcp.dto;

/**
 * MCP 接入配置：告诉前端 MCP server 脚本在哪、客户端配置该怎么填。
 *
 * @param projectRoot      应用运行目录，MCP 脚本相对它定位
 * @param serverPath       MCP server 脚本绝对路径
 * @param serverPathExists 脚本是否真实存在（false 时前端提示检查部署）
 * @param readmePath       使用说明文档路径（工具清单与完整配置示例）
 * @param pythonCommand    建议的解释器命令（随平台变化）
 * @param baseUrl          后端地址，对应客户端的 LUMINA_VAULT_URL
 */
public record McpConfigResponse(String projectRoot,
                                String serverPath,
                                boolean serverPathExists,
                                String readmePath,
                                String pythonCommand,
                                String baseUrl) {
}
