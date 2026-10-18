package com.lumi.vault.apikey;

import com.jayway.jsonpath.JsonPath;
import com.lumi.vault.asset.InMemoryAssetStorage;
import com.lumi.vault.business.asset.storage.AssetStorage;
import com.lumi.vault.business.auth.repository.TokenStore;
import com.lumi.vault.auth.InMemoryTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 密钥 + 开放 API 端到端：签发 → 鉴权 → 读写 → 吊销。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiKeyFlowTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AssetStorage assetStorage;

    @TestConfiguration(proxyBeanMethods = false)
    static class FakesConfiguration {

        @Bean
        @Primary
        TokenStore inMemoryTokenStore() {
            return new InMemoryTokenStore();
        }

        @Bean
        @Primary
        AssetStorage inMemoryAssetStorage() {
            return new InMemoryAssetStorage();
        }
    }

    @BeforeEach
    void resetStorage() {
        ((InMemoryAssetStorage) assetStorage).clear();
    }

    @Test
    void apiKeyAuthFlow() throws Exception {
        String token = register();

        // 1. 签发：明文只此一次
        MvcResult created = mockMvc.perform(post("/api/keys")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"mcp","scopes":["read","write"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.secret").isNotEmpty())
                .andExpect(jsonPath("$.data.key.scopes[0]").value("read"))
                .andExpect(jsonPath("$.data.key.active").value(true))
                .andReturn();
        String secret = read(created, "$.data.secret");
        long keyId = ((Number) JsonPath.read(
                created.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.key.id")).longValue();
        assertThat(secret).startsWith("lv_");

        // 2. 列表只暴露前缀，不暴露明文
        mockMvc.perform(get("/api/keys").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].keyPrefix").value(secret.substring(0, 12)))
                .andExpect(jsonPath("$.data[0].name").value("mcp"));

        // 3. 用密钥读取
        mockMvc.perform(get("/api/open/v1/account").header("X-API-Key", secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keyId").value(keyId))
                .andExpect(jsonPath("$.data.scopes[0]").value("read"))
                .andExpect(jsonPath("$.data.scopes[1]").value("write"));

        // 4. 用密钥写入：上传资产（也可以走 Authorization: Bearer lv_...）
        mockMvc.perform(multipart("/api/open/v1/assets")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", pngBytes(60, 40)))
                        .header(HttpHeaders.AUTHORIZATION, bearer(secret)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.originalFilename").value("photo.png"));

        // 5. 密钥统计可见
        mockMvc.perform(get("/api/open/v1/stats").header("X-API-Key", secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assets").value(1));

        // 6. 密钥不能管理密钥本身
        mockMvc.perform(get("/api/keys").header("X-API-Key", secret))
                .andExpect(status().isForbidden());

        // 7. 无效密钥 → 401
        mockMvc.perform(get("/api/open/v1/account").header("X-API-Key", "lv_not-a-real-key"))
                .andExpect(status().isUnauthorized());

        // 8. 无凭证 → 401
        mockMvc.perform(get("/api/open/v1/account"))
                .andExpect(status().isUnauthorized());

        // 9. 吊销后立即失效
        mockMvc.perform(delete("/api/keys/" + keyId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/open/v1/account").header("X-API-Key", secret))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readOnlyKeyCannotWrite() throws Exception {
        String token = register();
        MvcResult created = mockMvc.perform(post("/api/keys")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"reader","scopes":["read"]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String secret = read(created, "$.data.secret");

        mockMvc.perform(get("/api/open/v1/assets").header("X-API-Key", secret))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/open/v1/folders")
                        .header("X-API-Key", secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"只读也不能建"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void defaultScopesAndExpiry() throws Exception {
        String token = register();
        MvcResult created = mockMvc.perform(post("/api/keys")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"default","expiresInDays":7}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.key.scopes.length()").value(2))
                .andExpect(jsonPath("$.data.key.expiresAt").isNotEmpty())
                .andReturn();
        String secret = read(created, "$.data.secret");

        mockMvc.perform(multipart("/api/open/v1/assets")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", pngBytes(30, 30)))
                        .header("X-API-Key", secret))
                .andExpect(status().isCreated());

        // 非法 scope 被拒
        mockMvc.perform(post("/api/keys")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"bad","scopes":["admin"]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mcpConfigRequiresUserSession() throws Exception {
        String token = register();

        // 登录用户可读：返回本机脚本路径与建议客户端参数
        mockMvc.perform(get("/api/mcp/config").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.serverPath").isNotEmpty())
                .andExpect(jsonPath("$.data.readmePath").isNotEmpty())
                .andExpect(jsonPath("$.data.baseUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.pythonCommand").isNotEmpty());

        // API 密钥不能读部署路径（会暴露本机文件系统）
        MvcResult created = mockMvc.perform(post("/api/keys")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"reader","scopes":["read","write"]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String secret = read(created, "$.data.secret");

        mockMvc.perform(get("/api/mcp/config").header("X-API-Key", secret))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        // 未登录 → 401
        mockMvc.perform(get("/api/mcp/config"))
                .andExpect(status().isUnauthorized());
    }

    private String register() throws Exception {
        String username = "user" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"secret123","nickname":"Tester"}
                                """.formatted(username)))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result, "$.data.accessToken");
    }

    private String read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private byte[] pngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(30, 120, 200));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
