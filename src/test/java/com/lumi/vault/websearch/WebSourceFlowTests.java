package com.lumi.vault.websearch;

import com.jayway.jsonpath.JsonPath;
import com.lumi.vault.asset.InMemoryAssetStorage;
import com.lumi.vault.business.agent.cache.AssetEmbeddingCache;
import com.lumi.vault.business.agent.entity.AssetEmbedding;
import com.lumi.vault.business.agent.mapper.AssetEmbeddingMapper;
import com.lumi.vault.business.asset.entity.AppSetting;
import com.lumi.vault.business.asset.mapper.AppSettingMapper;
import com.lumi.vault.business.asset.storage.AssetStorage;
import com.lumi.vault.business.websearch.client.WebSourceException;
import com.lumi.vault.business.websearch.dto.WebSourceResult;
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
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "lumina.web-search.enabled=true",
        "lumina.web-search.api-key=test-key",
        "lumina.web-search.auto-on-upload=false",
        "lumina.web-search.request-delay-ms=0",
        "lumina.web-search.min-similarity=0.5"
})
@AutoConfigureMockMvc(addFilters = false)
@Transactional
class WebSourceFlowTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    FakeWebSourceLookupClient lookupClient;

    @Autowired
    AssetStorage assetStorage;

    @Autowired
    AssetEmbeddingMapper embeddingMapper;

    @Autowired
    AssetEmbeddingCache embeddingCache;

    @Autowired
    AppSettingMapper appSettingMapper;

    @Autowired
    ObjectMapper objectMapper;

    @TestConfiguration(proxyBeanMethods = false)
    static class FakesConfiguration {

        @Bean
        @Primary
        FakeWebSourceLookupClient fakeWebSourceLookupClient() {
            return new FakeWebSourceLookupClient();
        }

        @Bean
        @Primary
        AssetStorage inMemoryAssetStorage() {
            return new InMemoryAssetStorage();
        }
    }

    @BeforeEach
    void setUp() {
        lookupClient.reset();
        ((InMemoryAssetStorage) assetStorage).clear();
    }

    @Test
    void manualLookupStoresSourceAndAppliesEffects() throws Exception {
        String token = register();
        long assetId = upload(token, "source-me.png", pngBytes(60, 40));
        insertEmbedding(assetId, "动漫少女", List.of("动漫", "少女"));
        lookupClient.setResult(new WebSourceResult("saucenao", 0.95, "pixiv",
                "https://www.pixiv.net/artworks/123", "123", "作品标题", "画师A",
                List.of("hatsune_miku"), "{}"));

        mockMvc.perform(post("/api/assets/" + assetId + "/source-lookup")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FOUND"))
                .andExpect(jsonPath("$.data.sourceUrl").value("https://www.pixiv.net/artworks/123"))
                .andExpect(jsonPath("$.data.author").value("画师A"));

        mockMvc.perform(get("/api/assets/" + assetId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.author").value("画师A"));

        mockMvc.perform(get("/api/agent/analysis/" + assetId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags[?(@ == 'hatsune_miku')]").exists());

        mockMvc.perform(get("/api/assets/" + assetId + "/source").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FOUND"));
    }

    @Test
    void noMatchIsRecorded() throws Exception {
        String token = register();
        long assetId = upload(token, "no-match.png", pngBytes(61, 40));

        mockMvc.perform(post("/api/assets/" + assetId + "/source-lookup")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NO_MATCH"));

        mockMvc.perform(get("/api/assets/" + assetId + "/source").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NO_MATCH"));
    }

    @Test
    void quotaExceptionPausesFurtherLookups() throws Exception {
        String token = register();
        long assetId = upload(token, "quota.png", pngBytes(62, 40));
        lookupClient.setException(new WebSourceException("rate limited", true));

        mockMvc.perform(post("/api/assets/" + assetId + "/source-lookup")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("rate limited")));

        AppSetting setting = appSettingMapper.selectById("web-search.quota-exceeded-date");
        assertThat(setting).isNotNull();
        assertThat(setting.getSettingValue()).isEqualTo(LocalDate.now().toString());

        mockMvc.perform(post("/api/assets/" + assetId + "/source-lookup")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("配额")));

        mockMvc.perform(get("/api/assets/" + assetId + "/source").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUOTA"));
    }

    private void insertEmbedding(long assetId, String description, List<String> tags) {
        AssetEmbedding embedding = new AssetEmbedding();
        embedding.setAssetId(assetId);
        embedding.setDescription(description);
        embedding.setTags(objectMapper.writeValueAsString(tags));
        embedding.setStatus(AssetEmbedding.STATUS_DONE);
        embeddingMapper.insert(embedding);
        embeddingCache.refresh(assetId);
    }

    private String register() throws Exception {
        String username = "websrc_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"secret123"}
                                """.formatted(username)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.accessToken");
    }

    private long upload(String token, String filename, byte[] bytes) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/assets")
                        .file(new MockMultipartFile("file", filename, "image/png", bytes))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())
                .andReturn();
        Number id = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.id");
        return id.longValue();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(90, 140, 210));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
