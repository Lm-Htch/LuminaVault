package com.lumi.vault.business.mcp.service;

import com.lumi.vault.business.mcp.dto.McpConfigResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 探测本机部署形态，给出 MCP 客户端配置的建议值。
 *
 * <p>路径只有服务端知道（浏览器读不到文件系统），因此由后端统一给出，避免用户手填出错。
 */
@Service
public class McpConfigService {

    private static final String SERVER_RELATIVE = "mcp-server/lumina_vault_mcp.py";
    private static final String README_RELATIVE = "mcp-server/README.md";
    private static final String WINDOWS_PREFIX = "win";

    private final String baseUrl;

    public McpConfigService(@Value("${server.port:8080}") int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public McpConfigResponse describe() {
        Path root = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path server = root.resolve(SERVER_RELATIVE);
        return new McpConfigResponse(
                root.toString(),
                server.toString(),
                Files.isRegularFile(server),
                root.resolve(README_RELATIVE).toString(),
                pythonCommand(),
                baseUrl);
    }

    private String pythonCommand() {
        return System.getProperty("os.name", "").toLowerCase().contains(WINDOWS_PREFIX) ? "python" : "python3";
    }
}
